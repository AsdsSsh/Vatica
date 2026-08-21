package com.example.vatica.report;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.action.ActionExecutionService;
import com.example.vatica.action.ActionPlanView;
import com.example.vatica.artifact.ArtifactService;
import com.example.vatica.artifact.ArtifactView;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.permission.PermissionPolicyService;
import com.example.vatica.tool.DocumentTools;
import com.example.vatica.workspace.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代 26C：把周报预览推进为受控导出动作。
 *
 * <p>导出计划创建时冻结草案快照；批准后才认领 Word、Excel 和邮件草稿动作。
 * 邮件动作只把可编辑草稿保存到当前用户工作区，不调用 mail_send，也不连接外部邮箱。</p>
 */
@Service
public class WeeklyReportExportService {

    private static final String SUBJECT_TYPE = "WEEKLY_REPORT";

    private final WeeklyReportDraftService drafts;
    private final WeeklyReportExportRepository repository;
    private final ActionExecutionService actionExecutions;
    private final ArtifactService artifacts;
    private final DocumentTools documents;
    private final WorkspaceStore workspace;
    private final PermissionPolicyService permissionPolicy;
    private final ObjectMapper mapper;

    public WeeklyReportExportService(WeeklyReportDraftService drafts, WeeklyReportExportRepository repository,
            ActionExecutionService actionExecutions, ArtifactService artifacts, DocumentTools documents,
            WorkspaceStore workspace, PermissionPolicyService permissionPolicy, ObjectMapper mapper) {
        this.drafts = drafts;
        this.repository = repository;
        this.actionExecutions = actionExecutions;
        this.artifacts = artifacts;
        this.documents = documents;
        this.workspace = workspace;
        this.permissionPolicy = permissionPolicy;
        this.mapper = mapper;
    }

    @Transactional
    public WeeklyReportExportView prepare(String draftId, ExportRequest request) {
        RequestIdentity identity = RequestIdentityContext.require();
        WeeklyReportDraftService.WeeklyReportDraftView draft = drafts.get(draftId);
        Input input = Input.from(request, draft);
        repository.findByDraftIdAndUserId(draft.id(), identity.userId()).ifPresent(existing -> {
            throw new IllegalStateException("操作失败：该周报已有导出计划，请查看现有计划或重试失败动作。");
        });
        String exportId = UUID.randomUUID().toString();
        ActionPlanView plan = plan(draft, input, "DRAFT");
        WeeklyReportExportRecord record = new WeeklyReportExportRecord(exportId, identity, draft.id(), encode(plan),
                encode(draft), input.wordRequested(), input.excelRequested(), input.mailRequested(), input.mailTo(),
                input.mailSubject());
        WeeklyReportExportRecord saved = repository.save(record);
        return view(saved);
    }

    @Transactional
    public WeeklyReportExportView approve(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        WeeklyReportExportRecord record = owned(id, identity);
        if ("APPLIED".equals(record.getStatus())) return view(record);
        if (!"DRAFT".equals(record.getStatus())) {
            throw new IllegalStateException("操作失败：只有待批准的周报导出计划可以执行。");
        }
        ActionPlanView plan = decodePlan(record.getPlanJson());
        actionExecutions.approve(plan);
        record.markApproved();
        WeeklyReportExportRecord saved = repository.save(record);
        return execute(identity, saved);
    }

    @Transactional
    public WeeklyReportExportView retry(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        WeeklyReportExportRecord record = owned(id, identity);
        if (!"FAILED".equals(record.getStatus())) {
            throw new IllegalStateException("操作失败：只有失败的周报导出可以重试。");
        }
        ActionPlanView plan = decodePlan(record.getPlanJson());
        actionExecutions.requeueRecoverable(plan);
        record.resumeForRetry();
        WeeklyReportExportRecord saved = repository.save(record);
        return execute(identity, saved);
    }

    @Transactional
    public WeeklyReportExportView cancel(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        WeeklyReportExportRecord record = owned(id, identity);
        if ("APPLIED".equals(record.getStatus())) {
            throw new IllegalStateException("操作失败：已完成的周报导出不能取消。");
        }
        ActionPlanView plan = decodePlan(record.getPlanJson());
        actionExecutions.cancelNotStarted(plan);
        record.cancel();
        WeeklyReportExportRecord saved = repository.save(record);
        syncArtifacts(identity, saved, planFor(saved));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public WeeklyReportExportView get(String id) {
        return view(owned(id, RequestIdentityContext.require()));
    }

    @Transactional(readOnly = true)
    public List<WeeklyReportExportView> recent() {
        RequestIdentity identity = RequestIdentityContext.require();
        return repository.findTop20ByUserIdOrderByUpdatedAtDesc(identity.userId()).stream().map(this::view).toList();
    }

    private WeeklyReportExportView execute(RequestIdentity identity, WeeklyReportExportRecord record) {
        WeeklyReportDraftService.WeeklyReportDraftView draft = snapshot(record);
        ActionPlanView plan = planFor(record);
        for (ActionPlanView.ActionItemView action : plan.actions()) {
            try {
                if (actionExecutions.claim(plan, action.id()) == ActionExecutionService.Claim.ALREADY_SUCCEEDED) {
                    continue;
                }
                ensureWorkspaceWriteStillAllowed();
                String result = switch (action.type()) {
                    case "WRITE_DOCUMENT" -> documents.createWordReport(draft.title(), wordSections(draft),
                            wordFilename(record));
                    case "WRITE_TABLE" -> documents.createExcelStats("周报统计", "类型,事项,数值", excelRows(draft),
                            excelFilename(record));
                    case "CREATE_MAIL_DRAFT" -> writeMailDraft(identity, record, draft);
                    default -> throw new IllegalStateException("操作失败：不支持的周报导出动作 " + action.type() + "。");
                };
                actionExecutions.succeed(plan, action.id(), result);
            } catch (RuntimeException e) {
                String message = e.getMessage() == null ? "导出动作执行失败。" : e.getMessage();
                actionExecutions.fail(plan, action.id(), errorCode(action.type()), message);
                record.markFailed(message);
                WeeklyReportExportRecord saved = repository.save(record);
                syncArtifacts(identity, saved, planFor(saved));
                return view(saved);
            }
        }
        record.markApplied();
        WeeklyReportExportRecord saved = repository.save(record);
        syncArtifacts(identity, saved, planFor(saved));
        return view(saved);
    }

    private String writeMailDraft(RequestIdentity identity, WeeklyReportExportRecord record,
            WeeklyReportDraftService.WeeklyReportDraftView draft) {
        String body = mailBody(record, draft);
        String content = "收件人：" + (record.getMailTo() == null ? "" : record.getMailTo()) + "\n"
                + "主题：" + record.getMailSubject() + "\n\n" + body + "\n";
        try {
            return workspace.write(identity, mailFilename(record), new ByteArrayInputStream(
                    content.getBytes(StandardCharsets.UTF_8))).toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("操作失败：邮件草稿写入失败。" + e.getMessage(), e);
        }
    }

    private void ensureWorkspaceWriteStillAllowed() {
        if (permissionPolicy == null) return;
        boolean writable = permissionPolicy.current().workspaceRoots().stream().anyMatch(root -> root.write());
        if (!writable) {
            throw new IllegalStateException("当前工作区写权限已被回收，请重新授权后重试。");
        }
    }

    private WeeklyReportExportView view(WeeklyReportExportRecord record) {
        ActionPlanView plan = planFor(record);
        WeeklyReportDraftService.WeeklyReportDraftView draft = snapshot(record);
        String body = record.isMailRequested() ? mailBody(record, draft) : null;
        return new WeeklyReportExportView(record.getId(), record.getDraftId(), record.getStatus(), plan,
                record.getMailTo(), record.getMailSubject(), body,
                artifacts.listForSubject(RequestIdentityContext.require(), SUBJECT_TYPE, record.getDraftId()),
                record.getErrorMessage(), instant(record.getCreatedAt()), instant(record.getUpdatedAt()));
    }

    private void syncArtifacts(RequestIdentity identity, WeeklyReportExportRecord record, ActionPlanView plan) {
        artifacts.syncWeeklyReportExport(identity, record.getDraftId(), plan);
    }

    private ActionPlanView planFor(WeeklyReportExportRecord record) {
        ActionPlanView stored = decodePlan(record.getPlanJson());
        ActionPlanView plan = new ActionPlanView(stored.id(), stored.subjectType(), stored.subjectId(), stored.revision(),
                planStatus(record.getStatus()), stored.actions());
        ActionPlanView decorated = actionExecutions.decorate(plan);
        return decorated == null ? plan : decorated;
    }

    private static String planStatus(String status) {
        return switch (status) {
            case "APPLIED" -> "APPLIED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            case "APPROVED" -> "APPROVED";
            default -> "PREVIEW";
        };
    }

    private ActionPlanView plan(WeeklyReportDraftService.WeeklyReportDraftView draft, Input input, String status) {
        return ActionPlanView.weeklyReportExport(draft.id(), draft.title(), input.wordRequested(), input.excelRequested(),
                input.mailRequested(), input.mailTo(), wordFilename(draft.id()), excelFilename(draft.id()),
                mailFilename(draft.id()), status);
    }

    private WeeklyReportDraftService.WeeklyReportDraftView snapshot(WeeklyReportExportRecord record) {
        try {
            return mapper.readValue(record.getSnapshotJson(), WeeklyReportDraftService.WeeklyReportDraftView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：导出计划中的周报快照已损坏。", e);
        }
    }

    private ActionPlanView decodePlan(String json) {
        try {
            return mapper.readValue(json, ActionPlanView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：周报导出动作计划已损坏。", e);
        }
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：无法保存周报导出快照。", e);
        }
    }

    private WeeklyReportExportRecord owned(String id, RequestIdentity identity) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("操作失败：周报导出 ID 不能为空。");
        }
        return repository.findByIdAndUserId(id, identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：周报导出不存在或无权访问。"));
    }

    private static String wordSections(WeeklyReportDraftService.WeeklyReportDraftView draft) {
        String preview = draft.wordPreview();
        if (preview == null || preview.isBlank()) {
            throw new IllegalStateException("操作失败：Word 预览为空，无法导出。");
        }
        int newline = preview.indexOf('\n');
        return newline < 0 ? preview : preview.substring(newline + 1).trim();
    }

    private static String excelRows(WeeklyReportDraftService.WeeklyReportDraftView draft) {
        var facts = draft.facts();
        StringBuilder rows = new StringBuilder();
        addRow(rows, "统计", "会议数量", facts.statistics().meetingCount());
        addRow(rows, "统计", "待办总数", facts.statistics().todoCount());
        addRow(rows, "统计", "已完成待办", facts.statistics().completedTodoCount());
        addRow(rows, "统计", "未完成待办", facts.statistics().pendingTodoCount());
        addRow(rows, "统计", "逾期待办", facts.statistics().overdueTodoCount());
        for (WeeklyReportService.CalendarFact item : facts.calendar()) {
            addRow(rows, "日程", item.start(), item.title());
        }
        for (WeeklyReportService.TodoFact item : facts.todos()) {
            addRow(rows, "待办", item.due(), item.title() + (item.done() ? "（已完成）" : "（未完成）"));
        }
        return rows.toString().stripTrailing();
    }

    private static void addRow(StringBuilder rows, String type, String item, Object value) {
        rows.append(cleanCell(type)).append(',').append(cleanCell(item)).append(',').append(cleanCell(String.valueOf(value)))
                .append('\n');
    }

    private static String cleanCell(String value) {
        return value == null ? "" : value.replace(',', '，').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String mailBody(WeeklyReportExportRecord record,
            WeeklyReportDraftService.WeeklyReportDraftView draft) {
        if (draft.wordPreview() != null && !draft.wordPreview().isBlank()) return draft.wordPreview();
        return "# " + draft.title() + "\n\n本周会议数量：" + draft.facts().statistics().meetingCount()
                + "\n本周待办总数：" + draft.facts().statistics().todoCount();
    }

    private static String wordFilename(WeeklyReportExportRecord record) { return wordFilename(record.getDraftId()); }
    private static String excelFilename(WeeklyReportExportRecord record) { return excelFilename(record.getDraftId()); }
    private static String mailFilename(WeeklyReportExportRecord record) { return mailFilename(record.getDraftId()); }
    private static String wordFilename(String draftId) { return "weekly-report-" + draftId + ".docx"; }
    private static String excelFilename(String draftId) { return "weekly-report-" + draftId + ".xlsx"; }
    private static String mailFilename(String draftId) { return "weekly-report-" + draftId + "-mail-draft.md"; }

    private static String errorCode(String type) {
        return switch (type) {
            case "WRITE_DOCUMENT" -> "WORD_EXPORT_FAILED";
            case "WRITE_TABLE" -> "EXCEL_EXPORT_FAILED";
            case "CREATE_MAIL_DRAFT" -> "MAIL_DRAFT_WRITE_FAILED";
            default -> "WEEKLY_REPORT_EXPORT_FAILED";
        };
    }

    private static String instant(java.time.Instant value) { return value == null ? null : value.toString(); }

    private record Input(boolean wordRequested, boolean excelRequested, boolean mailRequested, String mailTo,
            String mailSubject) {
        static Input from(ExportRequest request, WeeklyReportDraftService.WeeklyReportDraftView draft) {
            if (request == null) throw new IllegalArgumentException("操作失败：周报导出请求不能为空。");
            boolean word = Boolean.TRUE.equals(request.wordRequested());
            boolean excel = Boolean.TRUE.equals(request.excelRequested());
            boolean mail = Boolean.TRUE.equals(request.mailRequested());
            if (!word && !excel && !mail) {
                throw new IllegalArgumentException("操作失败：至少选择 Word、Excel 或邮件草稿中的一项。");
            }
            String to = clean(request.mailTo(), "收件人", 1_000);
            String subject = clean(request.mailSubject(), "邮件主题", 240);
            if (mail && subject.isBlank()) subject = "周报：" + draft.title();
            if (to.contains("\n") || to.contains("\r") || subject.contains("\n") || subject.contains("\r")) {
                throw new IllegalArgumentException("操作失败：邮件收件人和主题不能包含换行符。");
            }
            return new Input(word, excel, mail, to, subject);
        }

        private static String clean(String value, String label, int max) {
            String result = value == null ? "" : value.trim();
            if (result.length() > max) throw new IllegalArgumentException("操作失败：" + label + "不能超过 " + max + " 个字符。");
            return result;
        }
    }

    public record ExportRequest(Boolean wordRequested, Boolean excelRequested, Boolean mailRequested, String mailTo,
            String mailSubject) { }

    public record WeeklyReportExportView(String id, String draftId, String status, ActionPlanView actionPlan,
            String mailTo, String mailSubject, String mailBody, List<ArtifactView> artifacts, String error,
            String createdAt, String updatedAt) { }
}
