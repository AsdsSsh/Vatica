package com.example.vatica.action;

import java.util.List;

/**
 * 迭代 25A：统一的副作用动作预览契约。
 *
 * <p>动作计划只描述用户将批准的变化，不承载模型思维链。幂等键由业务对象 ID
 * 和稳定动作序号组成，后续执行控制可据此恢复或去重。</p>
 */
public record ActionPlanView(String id, String subjectType, String subjectId, int revision,
        String status, List<ActionItemView> actions) {

    public record ActionItemView(String id, String type, String purpose, String target,
            String expectedChange, String inputSummary, String requiredPermission, String risk, String idempotencyKey,
            String approvalStatus, String executionStatus, String result) { }

    public static ActionPlanView meetingPreparation(String preparationId, String meetingTitle,
            String resultDocumentPath, List<String> todoTitles, List<String> todoIds, String preparationStatus) {
        String planId = "meeting-preparation:" + preparationId + ":v1";
        String approvalStatus = switch (preparationStatus) {
            case "APPLIED", "FAILED" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            case "CANCELLED" -> "CANCELLED";
            default -> "PENDING";
        };
        String documentExecutionStatus = executionStatus(preparationStatus, resultDocumentPath != null);
        String planStatus = switch (preparationStatus) {
            case "APPLIED" -> "APPLIED";
            case "FAILED" -> "FAILED";
            case "REJECTED" -> "REJECTED";
            case "CANCELLED" -> "CANCELLED";
            default -> "PREVIEW";
        };
        var actions = new java.util.ArrayList<ActionItemView>();
        actions.add(new ActionItemView("document", "WRITE_DOCUMENT", "保存会议准备文档",
                "当前用户工作区", "新增 meeting-preparation-" + preparationId + ".md（" + meetingTitle + "）",
                "已确认会议：" + meetingTitle, "workspace:write", "MEDIUM",
                "meeting-preparation:" + preparationId + ":document",
                approvalStatus, documentExecutionStatus,
                documentExecutionStatus.equals("SUCCEEDED") ? resultDocumentPath : null));
        for (int index = 0; index < todoTitles.size(); index++) {
            String actionId = "todo-" + (index + 1);
            boolean todoSucceeded = todoIds.size() > index;
            String executionStatus = executionStatus(preparationStatus, todoSucceeded);
            actions.add(new ActionItemView(actionId, "CREATE_TODO", "创建会议跟进待办",
                    "当前用户待办", "新增：" + todoTitles.get(index), "已确认会议：" + meetingTitle,
                    "pim:todo:write", "MEDIUM",
                    "meeting-preparation:" + preparationId + ":todo:" + (index + 1), approvalStatus,
                    executionStatus, todoSucceeded ? todoIds.get(index) : null));
        }
        return new ActionPlanView(planId, "MEETING_PREPARATION", preparationId, 1, planStatus,
                List.copyOf(actions));
    }

    /** 迭代 26C：周报文件导出和邮件草稿共用统一动作计划与幂等键。 */
    public static ActionPlanView weeklyReportExport(String draftId, String title, boolean wordRequested,
            boolean excelRequested, boolean mailRequested, String mailTo, String wordFilename,
            String excelFilename, String mailFilename, String exportStatus) {
        String planId = "weekly-report-export:" + draftId + ":v1";
        String approvalStatus = switch (exportStatus) {
            case "APPLIED", "FAILED" -> "APPROVED";
            case "CANCELLED" -> "CANCELLED";
            default -> "PENDING";
        };
        String planStatus = switch (exportStatus) {
            case "APPLIED" -> "APPLIED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            case "APPROVED" -> "APPROVED";
            default -> "PREVIEW";
        };
        var actions = new java.util.ArrayList<ActionItemView>();
        if (wordRequested) {
            actions.add(new ActionItemView("word", "WRITE_DOCUMENT", "导出 Word 周报",
                    "当前用户工作区", "新增 " + wordFilename, "已冻结周报草案：" + title,
                    "workspace:write", "MEDIUM", "weekly-report:" + draftId + ":word",
                    approvalStatus, executionStatus(exportStatus, false), null));
        }
        if (excelRequested) {
            actions.add(new ActionItemView("excel", "WRITE_TABLE", "导出 Excel 统计表",
                    "当前用户工作区", "新增 " + excelFilename, "已冻结周报统计事实：" + title,
                    "workspace:write", "MEDIUM", "weekly-report:" + draftId + ":excel",
                    approvalStatus, executionStatus(exportStatus, false), null));
        }
        if (mailRequested) {
            String recipient = mailTo == null || mailTo.isBlank() ? "待用户补充收件人" : mailTo;
            actions.add(new ActionItemView("mail", "CREATE_MAIL_DRAFT", "保存邮件草稿（不发送）",
                    "当前用户工作区", "新增 " + mailFilename + "；收件人：" + recipient,
                    "邮件正文由已冻结周报摘要生成", "workspace:write", "LOW",
                    "weekly-report:" + draftId + ":mail", approvalStatus,
                    executionStatus(exportStatus, false), null));
        }
        return new ActionPlanView(planId, "WEEKLY_REPORT", draftId, 1, planStatus, List.copyOf(actions));
    }

    private static String executionStatus(String preparationStatus, boolean succeeded) {
        return switch (preparationStatus) {
            case "APPLIED" -> "SUCCEEDED";
            case "FAILED" -> succeeded ? "SUCCEEDED" : "FAILED";
            case "REJECTED" -> "NOT_STARTED";
            case "CANCELLED" -> "CANCELLED";
            default -> "NOT_STARTED";
        };
    }

}
