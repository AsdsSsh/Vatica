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
