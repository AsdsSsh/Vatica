package com.example.vatica.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;
import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 迭代 23A：在计划审批前校验步骤、角色、Skill 与工具目录的能力交集。
 *
 * <p>Planner 提供 requiredTools，常见关键词仅作保守补全，最终工具授权仍由角色白名单、
 * Skill manifest、权限包装和执行期复核共同决定。
 */
public final class TaskCapabilityMatcher {

    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_.:-]{1,127}");

    private TaskCapabilityMatcher() {
    }

    public record Resolution(String agentId, ExecutionProfile skill, List<String> requiredTools,
            String explanation) {
        public Resolution {
            requiredTools = List.copyOf(requiredTools == null ? List.of() : requiredTools);
        }
    }

    /**
     * 合并 Planner 的结构化声明和确定性关键词补全。补全范围刻意很窄，避免把普通叙述误判为副作用。
     */
    public static List<String> requiredTools(TaskStep step) {
        LinkedHashSet<String> tools = new LinkedHashSet<>(normalize(step == null ? null : step.getRequiredTools()));
        String description = step == null || step.getDescription() == null
                ? "" : step.getDescription().toLowerCase(Locale.ROOT);
        if (containsAny(description, "计算", "求和", "加减乘除", "calculate", "sum")) {
            tools.add("calculator");
        }
        if (containsAny(description, "文本统计", "字数", "词频", "text_stats")) {
            tools.add("text_stats");
        }
        if (containsAny(description, "知识库", "检索资料", "检索文档", "search_knowledge_base")) {
            tools.add("search_knowledge_base");
        }
        return List.copyOf(tools);
    }

    /**
     * 返回可持久化的计划决议。专用 Skill 缺少步骤需要的工具时，统一回退到 general，
     * 不能带着必然失败的工具交集进入 HITL 或 Worker。
     */
    public static Resolution resolve(TaskStep step, AgentRegistry agents, ExecutionProfile candidate,
            Set<String> availableTools) {
        if (step == null) {
            throw new IllegalArgumentException("操作失败：计划步骤不能为空。");
        }
        List<String> required = requiredTools(step);
        Set<String> available = availableTools == null ? Set.of() : Set.copyOf(availableTools);
        List<String> unavailable = required.stream().filter(tool -> !available.contains(tool)).toList();
        if (!unavailable.isEmpty()) {
            throw new IllegalArgumentException("操作失败：步骤 " + step.getId() + " 要求的工具当前不可用（"
                    + String.join(", ", unavailable) + "）。请检查能力就绪状态后重新规划。");
        }

        String requestedRole = agents.normalizeId(step.getAgent());
        boolean roleCovers = required.stream().allMatch(tool -> agents.allowsTool(requestedRole, tool));
        boolean skillCovers = candidate == null || candidate.tools().containsAll(required);
        if (roleCovers && skillCovers) {
            return new Resolution(requestedRole, candidate, required, null);
        }

        List<String> reasons = new ArrayList<>();
        if (!roleCovers) {
            reasons.add("角色 " + requestedRole + " 未声明所需工具");
        }
        if (!skillCovers) {
            reasons.add("Skill " + candidate.id() + "@" + candidate.version() + " 未声明所需工具");
        }
        return new Resolution(AgentRegistry.GENERAL, null, required,
                String.join("；", reasons) + "，已在计划审批前回退为通用 Agent。");
    }

    /** 执行期再次复核，防止旧计划或运行中计划变更绕过审批前的匹配结果。 */
    public static void assertExecutionCompatible(TaskStep step, AgentRegistry agents, ExecutionProfile skill) {
        List<String> required = requiredTools(step);
        String agent = agents.normalizeId(step.getAgent());
        if (!required.stream().allMatch(tool -> agents.allowsTool(agent, tool))) {
            throw new IllegalStateException("操作失败：步骤 " + step.getId() + " 的角色 " + agent
                    + " 不具备所需工具（" + String.join(", ", required) + "）。");
        }
        if (skill != null && !skill.tools().containsAll(required)) {
            throw new IllegalStateException("操作失败：步骤 " + step.getId() + " 固定的 Skill " + skill.id() + "@"
                    + skill.version() + " 不具备所需工具（" + String.join(", ", required) + "）。");
        }
    }

    private static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!TOOL_NAME.matcher(normalized).matches()) {
                throw new IllegalArgumentException("操作失败：计划步骤的 requiredTools 包含非法工具名：" + value);
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
