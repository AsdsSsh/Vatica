package com.example.vatica.task;

import java.util.List;
import java.util.Locale;

import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 迭代 29C：副作用步骤的上下文门禁。
 *
 * <p>门禁只决定“是否需要人工确认”，不执行工具，也不把模型判断当成事实。只读步骤
 * 不受影响；副作用步骤在上下文包含确定性降级摘要、待裁决条目或待刷新事实时暂停，
 * 用户批准后由 {@code contextGateApproved} 记录一次明确的上下文确认。</p>
 */
public final class ContextGate {

    private ContextGate() {
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision requireApproval(String reason) {
            return new Decision(false, reason);
        }
    }

    public static Decision evaluate(TaskPlan plan, TaskStep step, boolean staleFacts) {
        if (step == null || !isSideEffectStep(step)) {
            return Decision.allow();
        }
        if (!step.isApproved()) {
            return Decision.requireApproval("副作用步骤尚未获得人工批准");
        }
        if (step.isContextGateApproved()) {
            return Decision.allow();
        }
        if (staleFacts) {
            return Decision.requireApproval("任务关键事实存在待刷新来源，请重新读取来源后确认");
        }
        if (hasOpenEntry(plan, step.getId())) {
            return Decision.requireApproval("该步骤仍有未解决的人工/Agent 仲裁项");
        }
        for (Integer dependencyId : dependencies(step)) {
            TaskStep dependency = find(plan, dependencyId);
            if (dependency == null || !TaskBlackboard.hasResult(dependency)) {
                return Decision.requireApproval("副作用步骤缺少已完成的直接依赖事实");
            }
            if (TaskBlackboard.DIGEST_SOURCE_DETERMINISTIC_FALLBACK
                    .equals(dependency.getResultDigestSource())) {
                return Decision.requireApproval("直接依赖仅有确定性降级摘要，请确认原始来源后再写入");
            }
        }
        return Decision.allow();
    }

    /** 供调度器在副作用步骤未声明 needsApproval 时仍能机械识别高风险动作。 */
    public static boolean isSideEffectStep(TaskStep step) {
        if (step == null) return false;
        if (step.isNeedsApproval() || (step.getWriteResources() != null && !step.getWriteResources().isEmpty())) {
            return true;
        }
        List<String> tools = step.getRequiredTools();
        if (tools != null && tools.stream().map(ContextGate::normalize).anyMatch(ContextGate::sideEffectTool)) {
            return true;
        }
        String text = normalize(step.getDescription());
        return text.contains("write_file") || text.contains("mail_send") || text.contains("发送邮件")
                || text.contains("calendar_create") || text.contains("calendar_import")
                || text.contains("创建待办") || text.contains("删除") || text.contains("覆盖");
    }

    private static boolean sideEffectTool(String tool) {
        return tool.equals("write_file") || tool.equals("mail_send") || tool.equals("calendar_create")
                || tool.equals("calendar_import") || tool.equals("todo_create") || tool.equals("todo_complete")
                || tool.equals("delete_file") || tool.equals("write_document") || tool.equals("write_table");
    }

    private static boolean hasOpenEntry(TaskPlan plan, int stepId) {
        return plan != null && plan.getBlackboard() != null && plan.getBlackboard().stream()
                .anyMatch(entry -> BlackboardEntry.OPEN.equals(entry.status())
                        && entry.relatedStepIds() != null && entry.relatedStepIds().contains(stepId));
    }

    private static List<Integer> dependencies(TaskStep step) {
        if (step.getDependsOn() == null) return step.getId() <= 1 ? List.of() : List.of(step.getId() - 1);
        return step.getDependsOn();
    }

    private static TaskStep find(TaskPlan plan, int id) {
        if (plan == null || plan.getSteps() == null) return null;
        return plan.getSteps().stream().filter(candidate -> candidate.getId() == id).findFirst().orElse(null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
