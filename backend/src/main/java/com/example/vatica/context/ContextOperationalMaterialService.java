package com.example.vatica.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.vatica.action.ActionExecutionRecord;
import com.example.vatica.action.ActionExecutionRecordRepository;
import com.example.vatica.artifact.ArtifactRecord;
import com.example.vatica.artifact.ArtifactRepository;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.task.BlackboardEntry;
import com.example.vatica.task.TaskBlackboard;
import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.example.vatica.task.TaskRecord;
import com.example.vatica.task.TaskRecordRepository;
import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代 33：读取摘要失败时仍可恢复的操作事实。
 *
 * <p>所有查询都带当前用户和组织；服务只返回受控摘要，不把完整工具参数、任务原文或产物正文
 * 放进模型上下文。没有显式 taskId 时只尝试用 sessionId 作为同名业务范围，不跨用户猜测最近任务。</p>
 */
@Service
public class ContextOperationalMaterialService {

    private static final int MAX_TASK_STEPS = 8;
    private static final int MAX_BLACKBOARD_ENTRIES = 8;
    private static final int MAX_TOOL_RECORDS = 8;
    private static final int MAX_ACTION_RECORDS = 8;
    private static final int MAX_ARTIFACT_RECORDS = 8;
    private static final int MAX_ID_CHARS = 128;

    private final TaskRecordRepository tasks;
    private final AgentTraceRecordRepository traces;
    private final ActionExecutionRecordRepository actions;
    private final ArtifactRepository artifacts;
    private final ObjectMapper mapper;

    public ContextOperationalMaterialService(TaskRecordRepository tasks,
            AgentTraceRecordRepository traces, ActionExecutionRecordRepository actions,
            ArtifactRepository artifacts, ObjectMapper mapper) {
        this.tasks = tasks;
        this.traces = traces;
        this.actions = actions;
        this.artifacts = artifacts;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * 按当前会话和可选任务范围读取工具、审批、任务状态及交付物索引。
     * {@code required=true} 表示调用方已经处于降级路径，即使没有记录也要注入“无记录”边界。
     */
    public ContextOperationalMaterials resolveForChat(String sessionId, String taskId, boolean required) {
        LookupState state = new LookupState();
        List<ContextOperationalMaterials.Snippet> snippets = new ArrayList<>();
        RequestIdentity identity;
        try {
            identity = RequestIdentityContext.require();
        } catch (RuntimeException exception) {
            return new ContextOperationalMaterials(List.of(), required, true);
        }

        List<String> scopeIds = ids(sessionId, taskId);
        TaskRecord task = findTask(identity, scopeIds, state);
        if (task != null) {
            addTask(snippets, task, state);
            loadTools(snippets, identity, task.getId(), state);
        }
        loadActions(snippets, identity, scopeIds, state);
        loadArtifacts(snippets, identity, scopeIds, state);
        return new ContextOperationalMaterials(snippets, required, state.failed);
    }

    private TaskRecord findTask(RequestIdentity identity, List<String> candidates, LookupState state) {
        if (tasks == null) {
            return null;
        }
        for (String candidate : candidates) {
            try {
                Optional<TaskRecord> found = tasks.findByIdAndUserIdAndOrgId(candidate,
                        identity.userId(), identity.orgId());
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (RuntimeException exception) {
                state.failed = true;
            }
        }
        return null;
    }

    private void addTask(List<ContextOperationalMaterials.Snippet> snippets, TaskRecord record,
            LookupState state) {
        String stateText = "status=" + name(record.getStatus())
                + "; currentStep=" + record.getCurrentStep()
                + "; pendingStepId=" + record.getPendingStepId()
                + "; score=" + value(record.getScore())
                + "; verdict=" + name(record.getVerdict())
                + "; recoveryApprovalRequired=" + record.isRecoveryApprovalRequired();
        add(snippets, ContextOperationalMaterials.TASK_STATE, "task:" + record.getId(), stateText);
        if (record.getError() != null && !record.getError().isBlank()) {
            add(snippets, ContextOperationalMaterials.TASK_STATE, "task:" + record.getId() + "/error",
                    record.getError());
        }

        TaskPlan plan;
        try {
            plan = mapper.readValue(record.getPlanJson(), TaskPlan.class);
        } catch (Exception exception) {
            state.failed = true;
            return;
        }
        if (plan == null) {
            return;
        }
        List<TaskStep> steps = plan.getSteps() == null ? List.of() : plan.getSteps();
        for (TaskStep step : steps.stream().limit(MAX_TASK_STEPS).toList()) {
            String digest = step.getResultDigest();
            if (digest == null || digest.isBlank()) {
                digest = TaskBlackboard.hasResult(step) ? "已有结果（摘要不可用，需回源确认）" : "尚未完成";
            }
            String detail = "description=" + safe(step.getDescription(), 180)
                    + "; requiredTools=" + safe(String.valueOf(step.getRequiredTools()), 180)
                    + "; result=" + safe(digest, 240);
            add(snippets, ContextOperationalMaterials.TASK_STATE,
                    "task:" + record.getId() + "/step:" + step.getId(), detail);
            if (step.isNeedsApproval()) {
                String approval = step.isApproved() ? "APPROVED" : "PENDING";
                if (step.isContextGateApproved()) {
                    approval += "+CONTEXT_GATE";
                }
                add(snippets, ContextOperationalMaterials.APPROVAL,
                        "task:" + record.getId() + "/step:" + step.getId(),
                        "description=" + safe(step.getDescription(), 180), approval);
            }
            if (step.getRequiredTools() != null) {
                for (String tool : step.getRequiredTools().stream().limit(4).toList()) {
                    if (tool != null && !tool.isBlank()) {
                        add(snippets, ContextOperationalMaterials.TOOL,
                                "task:" + record.getId() + "/declared-tool:" + safe(tool, 100),
                                "DECLARED", "步骤 " + step.getId() + " 声明使用该工具");
                    }
                }
            }
        }
        if (plan.getBlackboard() != null) {
            plan.getBlackboard().stream()
                    .filter(entry -> entry != null)
                    .filter(entry -> BlackboardEntry.OPEN.equals(entry.status())
                            || BlackboardEntry.NEED_HELP.equals(entry.type())
                            || BlackboardEntry.CONFLICT.equals(entry.type()))
                    .limit(MAX_BLACKBOARD_ENTRIES)
                    .forEach(entry -> add(snippets, ContextOperationalMaterials.APPROVAL,
                            "task:" + record.getId() + "/blackboard:" + entry.type(),
                            entry.status(), safe(entry.content(), 260)));
        }
    }

    private void loadTools(List<ContextOperationalMaterials.Snippet> snippets, RequestIdentity identity,
            String taskId, LookupState state) {
        if (traces == null) {
            return;
        }
        try {
            traces.findByUserIdAndOrgIdAndTaskIdOrderByCreatedAtDesc(identity.userId(), identity.orgId(), taskId,
                    PageRequest.of(0, MAX_TOOL_RECORDS)).stream()
                    .filter(record -> record != null)
                    .filter(record -> !"executor.thinking".equals(record.getToolName()))
                    .limit(MAX_TOOL_RECORDS)
                    .forEach(record -> {
                        String detail = "step=" + value(record.getStepId())
                                + "; output=" + safe(record.getOutputSummary(), 260)
                                + "; durationMs=" + record.getDurationMs()
                                + "; outputLength=" + record.getOutputLength();
                        if (record.getError() != null && !record.getError().isBlank()) {
                            detail += "; error=" + safe(record.getError(), 180);
                        }
                        add(snippets, ContextOperationalMaterials.TOOL,
                                "task:" + taskId + "/tool:" + safe(record.getToolName(), 140),
                                safe(record.getStatus(), 60), detail);
                    });
        } catch (RuntimeException exception) {
            state.failed = true;
        }
    }

    private void loadActions(List<ContextOperationalMaterials.Snippet> snippets, RequestIdentity identity,
            List<String> scopeIds, LookupState state) {
        if (actions == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String scopeId : scopeIds) {
            try {
                actions.findByUserIdAndOrgIdAndSubjectIdOrderByUpdatedAtDesc(identity.userId(), identity.orgId(),
                        scopeId, PageRequest.of(0, MAX_ACTION_RECORDS)).stream()
                        .filter(record -> record != null && seen.add(record.getId()))
                        .limit(MAX_ACTION_RECORDS)
                        .forEach(record -> {
                            String detail = "subject=" + safe(record.getSubjectType(), 80) + "/"
                                    + safe(record.getSubjectId(), 120)
                                    + "; permission=" + safe(record.getRequiredPermission(), 100)
                                    + "; attempts=" + record.getAttemptCount();
                            String result = record.getResult();
                            if (result == null || result.isBlank()) {
                                result = record.getErrorMessage();
                            }
                            if (result != null && !result.isBlank()) {
                                detail += "; result=" + safe(result, 240);
                            }
                            add(snippets, ContextOperationalMaterials.APPROVAL,
                                    "action:" + safe(record.getActionId(), 120),
                                    safe(record.getStatus() == null ? null : record.getStatus().name(), 60), detail);
                        });
            } catch (RuntimeException exception) {
                state.failed = true;
            }
        }
    }

    private void loadArtifacts(List<ContextOperationalMaterials.Snippet> snippets, RequestIdentity identity,
            List<String> scopeIds, LookupState state) {
        if (artifacts == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String scopeId : scopeIds) {
            try {
                artifacts.findByUserIdAndOrgIdAndSubjectIdOrderByUpdatedAtDesc(identity.userId(), identity.orgId(),
                        scopeId, PageRequest.of(0, MAX_ARTIFACT_RECORDS)).stream()
                        .filter(record -> record != null && seen.add(record.getId()))
                        .limit(MAX_ARTIFACT_RECORDS)
                        .forEach(record -> add(snippets, ContextOperationalMaterials.ARTIFACT,
                                safe(record.getType(), 80) + "/" + safe(record.getName(), 180),
                                record.getStatus() == null ? "" : record.getStatus().name(),
                                "subject=" + safe(record.getSubjectType(), 80) + "/"
                                        + safe(record.getSubjectId(), 120)
                                        + "; locator=" + safe(record.getLocator(), 260)
                                        + "; summary=" + safe(record.getSummary(), 240)
                                        + "; sourceActionId=" + safe(record.getSourceActionId(), 120)));
            } catch (RuntimeException exception) {
                state.failed = true;
            }
        }
    }

    private static List<String> ids(String sessionId, String taskId) {
        Set<String> values = new LinkedHashSet<>();
        addId(values, taskId);
        addId(values, sessionId);
        return List.copyOf(values);
    }

    private static void addId(Set<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (normalized.length() <= MAX_ID_CHARS) {
            values.add(normalized);
        }
    }

    private static void add(List<ContextOperationalMaterials.Snippet> snippets, String category, String key,
            String status, String detail) {
        snippets.add(new ContextOperationalMaterials.Snippet(category, key, status, detail));
    }

    private static void add(List<ContextOperationalMaterials.Snippet> snippets, String category, String key,
            String detail) {
        add(snippets, category, key, "", detail);
    }

    private static String safe(String value, int max) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private static String name(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static final class LookupState {
        private boolean failed;
    }
}
