package com.example.vatica.context;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.controller.ChatSessionRecord;
import com.example.vatica.controller.ChatSessionRecordRepository;
import com.example.vatica.controller.SessionMemory;
import com.example.vatica.controller.SessionMemory.ContextWindow;
import com.example.vatica.controller.SessionSummaryFailureCode;
import com.example.vatica.controller.SessionSummaryStatus;
import com.example.vatica.task.TaskRecord;
import com.example.vatica.task.TaskRecordRepository;
import com.example.vatica.task.TaskStatus;

/**
 * 迭代 29D：把摘要、短期滑窗、关键事实和副作用门禁压缩成一个安全的健康信号。
 *
 * <p>健康查询是只读诊断，不写 Span，不触碰 Prompt；所有持久化查询均使用当前用户和组织快照。</p>
 */
@Service
public class ContextHealthService {

    private static final Logger log = LoggerFactory.getLogger(ContextHealthService.class);

    private final SessionMemory sessionMemory;
    private final ChatSessionRecordRepository sessions;
    private final ContextFactService facts;
    private final TaskRecordRepository tasks;

    public ContextHealthService(SessionMemory sessionMemory, ChatSessionRecordRepository sessions,
            ContextFactService facts, TaskRecordRepository tasks) {
        this.sessionMemory = sessionMemory;
        this.sessions = sessions;
        this.facts = facts;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public ContextHealthView get(ContextFactScopeType scopeType, String scopeId) {
        if (scopeType == null) {
            throw new IllegalArgumentException("操作失败：上下文范围不能为空。");
        }
        String id = normalizeId(scopeId);
        return switch (scopeType) {
            case CHAT_SESSION -> session(id);
            case TASK -> task(id);
            case SUBJECT -> throw new IllegalArgumentException("操作失败：当前不支持 SUBJECT 健康查询。");
        };
    }

    /** 纯规则分类器供故障注入和单测复用；stale facts 优先于摘要状态。 */
    static ContextHealthStatus classifySession(SessionSummaryStatus summaryStatus, boolean fallbackHistory,
            long requestedThrough, long summaryThrough, int staleFactCount) {
        if (staleFactCount > 0) return ContextHealthStatus.NEEDS_REFRESH;
        if (summaryStatus == SessionSummaryStatus.FAILED || fallbackHistory) {
            return ContextHealthStatus.DEGRADED;
        }
        if (summaryStatus == SessionSummaryStatus.PENDING && requestedThrough > summaryThrough) {
            return ContextHealthStatus.PROCESSING;
        }
        return ContextHealthStatus.HEALTHY;
    }

    static ContextHealthStatus classifyTask(TaskStatus taskStatus, boolean gatePending, int staleFactCount,
            boolean factsUnavailable) {
        if (staleFactCount > 0) return ContextHealthStatus.NEEDS_REFRESH;
        if (gatePending || factsUnavailable) return ContextHealthStatus.DEGRADED;
        if (taskStatus == TaskStatus.RUNNING || taskStatus == TaskStatus.RETRY
                || taskStatus == TaskStatus.REVIEW) return ContextHealthStatus.PROCESSING;
        return ContextHealthStatus.HEALTHY;
    }

    private ContextHealthView session(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        ChatSessionRecord record = sessions.findByUserIdAndOrgIdAndSessionId(
                identity.userId(), identity.orgId(), id)
                .orElseThrow(() -> new IllegalArgumentException("操作失败：会话不存在或无权访问。"));
        ContextWindow window;
        boolean memoryUnavailable = false;
        try {
            window = sessionMemory.contextWindow(id);
        } catch (RuntimeException e) {
            // 归属已先校验；短期记忆故障只让健康降级，不把诊断入口变成新的故障源。
            memoryUnavailable = true;
            log.warn("会话 {} 上下文窗口读取失败，健康状态降级", id, e);
            window = new ContextWindow(null, record.getSummaryStatus(), record.getSummaryThroughSeq(),
                    record.getSummaryRequestedThroughSeq(), 0, List.of(), List.of(), List.of());
        }
        FactCounts count = factCounts(identity, ContextFactScopeType.CHAT_SESSION, id);
        ContextHealthStatus status = count.stale() > 0 ? ContextHealthStatus.NEEDS_REFRESH
                : memoryUnavailable || count.unavailable() ? ContextHealthStatus.DEGRADED
                : classifySession(record.getSummaryStatus(), window.hasFallbackHistory(),
                        record.getSummaryRequestedThroughSeq(), record.getSummaryThroughSeq(), count.stale());
        String reason = count.stale() > 0 ? "FACTS_NEED_REFRESH"
                : memoryUnavailable ? "MEMORY_UNAVAILABLE"
                : count.unavailable() ? "FACTS_UNAVAILABLE" : sessionReason(status, record);
        return new ContextHealthView("CHAT_SESSION", id, status, record.getSummaryStatus(),
                record.getSummaryFailureCode(), record.getSummaryThroughSeq(),
                record.getSummaryRequestedThroughSeq(), window.uncoveredMessageCount(),
                window.uncoveredHead().size(), window.uncoveredTail().size(), window.recent().size(),
                record.getSummaryAttemptCount(), record.getSummaryLastAttemptAt(),
                record.getSummaryLastSuccessAt(), record.getSummaryNextRetryAt(), count.current(), count.stale(),
                false, reason, Instant.now());
    }

    private ContextHealthView task(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        TaskRecord record = tasks.findByIdAndUserIdAndOrgId(id, identity.userId(), identity.orgId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：任务不存在或无权访问。"));
        FactCounts count = factCounts(identity, ContextFactScopeType.TASK, id);
        boolean gatePending = record.getStatus() == TaskStatus.PENDING_APPROVAL
                && record.getError() != null && record.getError().startsWith("上下文门禁：");
        ContextHealthStatus status = classifyTask(record.getStatus(), gatePending, count.stale(), count.unavailable());
        String reason = count.unavailable() ? "FACTS_UNAVAILABLE"
                : gatePending ? "CONTEXT_GATE_PENDING"
                : status == ContextHealthStatus.NEEDS_REFRESH ? "FACTS_NEED_REFRESH"
                : status == ContextHealthStatus.PROCESSING ? "TASK_RUNNING" : "NONE";
        return new ContextHealthView("TASK", id, status, null, null, 0, 0, 0, 0, 0, 0, 0,
                null, null, null, count.current(), count.stale(), gatePending, reason, Instant.now());
    }

    private FactCounts factCounts(RequestIdentity identity, ContextFactScopeType scopeType, String scopeId) {
        try {
            List<ContextFactRecord> active = facts.listActive(identity, scopeType, scopeId);
            // resolveCurrent() deliberately reuses the request identity and filters expired/refresh-needed facts.
            List<ContextFactRecord> current = RequestIdentityContext.callWith(identity,
                    () -> facts.resolveCurrent(scopeType, scopeId));
            return new FactCounts(current.size(), Math.max(0, active.size() - current.size()), false);
        } catch (RuntimeException e) {
            // 健康接口必须能报告事实层故障；不能因诊断查询失败而伪装成健康。
            return new FactCounts(0, 0, true);
        }
    }

    private static String sessionReason(ContextHealthStatus status, ChatSessionRecord record) {
        return switch (status) {
            case HEALTHY -> "NONE";
            case PROCESSING -> "SUMMARY_PROCESSING";
            case DEGRADED -> record.getSummaryStatus() == SessionSummaryStatus.FAILED
                    ? "SUMMARY_FAILED_" + safeFailure(record.getSummaryFailureCode())
                    : "SUMMARY_GAP_VISIBLE";
            case NEEDS_REFRESH -> "FACTS_NEED_REFRESH";
        };
    }

    private static String safeFailure(SessionSummaryFailureCode code) {
        return code == null ? SessionSummaryFailureCode.UNKNOWN.name() : code.name();
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank() || id.trim().length() > 64) {
            throw new IllegalArgumentException("操作失败：上下文范围 ID 不合法。");
        }
        return id.trim();
    }

    private record FactCounts(int current, int stale, boolean unavailable) {
    }
}
