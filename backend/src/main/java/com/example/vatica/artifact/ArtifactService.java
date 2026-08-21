package com.example.vatica.artifact;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.action.ActionPlanView;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 25C：统一维护场景产物索引，保留失败记录而不清除审计事实。 */
@Service
public class ArtifactService {

    public static final String MEETING_PREPARATION = "MEETING_PREPARATION";
    public static final String WEEKLY_REPORT = "WEEKLY_REPORT";

    private final ArtifactRepository repository;

    public ArtifactService(ArtifactRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(String subjectType, String subjectId) {
        RequestIdentity identity = RequestIdentityContext.require();
        if (subjectType == null || subjectType.isBlank() || subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("操作失败：产物查询必须提供来源类型和来源 ID。");
        }
        return listForSubject(identity, subjectType, subjectId);
    }

    /** 已持有请求身份的业务服务可复用，避免为查询产物重复读取 ThreadLocal。 */
    @Transactional(readOnly = true)
    public List<ArtifactView> listForSubject(RequestIdentity identity, String subjectType, String subjectId) {
        if (identity == null || identity.userId() == null) {
            throw new IllegalArgumentException("操作失败：产物查询缺少用户身份。");
        }
        return repository.findByUserIdAndSubjectTypeAndSubjectIdOrderByUpdatedAtDesc(identity.userId(), subjectType,
                subjectId).stream().map(ArtifactView::from).toList();
    }

    /** 会议准备每次状态变化都重建当前索引；失败键只更新为最新错误，历史动作记录仍单独保留。 */
    @Transactional
    public void syncMeetingPreparation(RequestIdentity identity, String subjectId, ActionPlanView plan,
            String preparationStatus, String error) {
        upsert(identity, subjectId, MEETING_PREPARATION, subjectId + ":draft", "DRAFT", "会议准备草案", null,
                draftStatus(preparationStatus), "审批前的结构化会议准备草案", null, null);
        for (ActionPlanView.ActionItemView action : plan.actions()) {
            String type = "WRITE_DOCUMENT".equals(action.type()) ? "DOCUMENT" : "TODO";
            ArtifactStatus status = artifactStatus(action);
            String name = "DOCUMENT".equals(type) ? "会议准备文档" : action.expectedChange();
            upsert(identity, subjectId, MEETING_PREPARATION, action.idempotencyKey(), type, name, action.result(), status,
                    action.result() == null ? action.expectedChange() : "已由动作 " + action.id() + " 生成", action.id(),
                    action.idempotencyKey());
        }
        if (error != null && !error.isBlank()) {
            upsert(identity, subjectId, MEETING_PREPARATION, subjectId + ":failure", "FAILURE", "会议准备执行失败",
                    null, ArtifactStatus.FAILED, error, null, null);
        }
    }

    /** 26B：周报草案只建立预览索引；26C 批准导出后再把文档/表格推进为 READY。 */
    @Transactional
    public void syncWeeklyReportDraft(RequestIdentity identity, String subjectId, boolean wordRequested,
            boolean excelRequested, String wordPreview, String excelPreview) {
        upsert(identity, subjectId, WEEKLY_REPORT, subjectId + ":draft", "DRAFT", "周报结构化草案", null,
                ArtifactStatus.PREVIEW, "事实快照和用户编辑字段已保存", null, null);
        upsert(identity, subjectId, WEEKLY_REPORT, subjectId + ":word-preview", "DOCUMENT", "Word 周报模板预览",
                null, wordRequested ? ArtifactStatus.PREVIEW : ArtifactStatus.CANCELLED,
                wordRequested ? "等待 26C 批准后导出，预览字符数 " + length(wordPreview) : "用户未选择 Word 交付物",
                null, subjectId + ":word");
        upsert(identity, subjectId, WEEKLY_REPORT, subjectId + ":excel-preview", "TABLE", "Excel 统计模板预览",
                null, excelRequested ? ArtifactStatus.PREVIEW : ArtifactStatus.CANCELLED,
                excelRequested ? "等待 26C 批准后导出，预览字符数 " + length(excelPreview) : "用户未选择 Excel 交付物",
                null, subjectId + ":excel");
    }

    /** 26C：导出动作完成后更新同一份周报产物索引；邮件只记录本地草稿，不代表已经发送。 */
    @Transactional
    public void syncWeeklyReportExport(RequestIdentity identity, String subjectId, ActionPlanView plan) {
        for (ActionPlanView.ActionItemView action : plan.actions()) {
            String artifactKey;
            String type;
            String name;
            switch (action.type()) {
                case "WRITE_DOCUMENT" -> {
                    artifactKey = subjectId + ":word-preview";
                    type = "DOCUMENT";
                    name = "Word 周报";
                }
                case "WRITE_TABLE" -> {
                    artifactKey = subjectId + ":excel-preview";
                    type = "TABLE";
                    name = "Excel 统计表";
                }
                case "CREATE_MAIL_DRAFT" -> {
                    artifactKey = subjectId + ":mail-draft";
                    type = "MAIL_DRAFT";
                    name = "邮件草稿";
                }
                default -> {
                    continue;
                }
            }
            ArtifactStatus status = plan.status().equals("CANCELLED") ? ArtifactStatus.CANCELLED
                    : artifactStatus(action);
            String summary = switch (status) {
                case READY -> "已由受控导出动作 " + action.id() + " 写入工作区。";
                case FAILED -> action.result() == null ? "导出失败，保留失败动作供重试。" : action.result();
                case CANCELLED -> "未执行或已取消；周报草案和已有产物仍保留。";
                case APPROVED -> "已批准，等待导出动作执行。";
                default -> "等待用户批准导出动作。";
            };
            upsert(identity, subjectId, WEEKLY_REPORT, artifactKey, type, name,
                    "SUCCEEDED".equals(action.executionStatus()) ? action.result() : null, status, summary,
                    action.id(), action.idempotencyKey());
        }
    }

    private void upsert(RequestIdentity identity, String subjectId, String subjectType, String artifactKey, String type,
            String name, String locator, ArtifactStatus status, String summary, String actionId, String idempotencyKey) {
        ArtifactRecord record = repository.findByUserIdAndArtifactKey(identity.userId(), artifactKey).orElse(null);
        if (record == null) {
            record = new ArtifactRecord(UUID.randomUUID().toString(), identity, subjectType, subjectId, type, artifactKey,
                    name, locator, status, summary, actionId, idempotencyKey);
        } else {
            record.update(name, locator, status, summary, actionId, idempotencyKey);
        }
        repository.save(record);
    }

    private static ArtifactStatus draftStatus(String status) {
        return switch (status) {
            case "APPLIED" -> ArtifactStatus.READY;
            case "FAILED" -> ArtifactStatus.FAILED;
            case "REJECTED" -> ArtifactStatus.REJECTED;
            case "CANCELLED" -> ArtifactStatus.CANCELLED;
            default -> ArtifactStatus.PREVIEW;
        };
    }

    private static ArtifactStatus artifactStatus(ActionPlanView.ActionItemView action) {
        return switch (action.executionStatus()) {
            case "SUCCEEDED" -> ArtifactStatus.READY;
            case "FAILED" -> ArtifactStatus.FAILED;
            case "CANCELLED" -> ArtifactStatus.CANCELLED;
            default -> "APPROVED".equals(action.approvalStatus()) ? ArtifactStatus.APPROVED : ArtifactStatus.PREVIEW;
        };
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
