package com.example.vatica.agentscope;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

/**
 * 迭代 30C：把 Vatica 的请求级工具白名单映射为 AgentScope Toolkit 工具组。
 *
 * <p>AgentScope 的分组不仅影响发送给模型的 schema，也会在 {@code callTool}
 * 执行前检查活动组。适配器因此把候选工具全部放入“允许/拒绝”两个组，而不是只
 * 从 schema 中删除工具，避免模型或其他调用方通过工具名绕过白名单。传入的工具
 * 应先经过 Vatica 的 {@code PermissionBoundToolCallbacks} 包装；本类只负责工具可见性，
 * 不拥有身份、工作区权限或审批事实。
 */
public final class AgentScopeToolGroupAdapter {

    private static final AtomicLong GROUP_SEQUENCE = new AtomicLong();
    private static final String GROUP_PREFIX = "vatica-request-tools-";

    private AgentScopeToolGroupAdapter() {
    }

    /**
     * 将候选工具注册到请求级 Toolkit，并激活允许工具组。
     *
     * <p>与现有 AgentScopeRuntime 的约定一致：{@code null} 或空白名单表示“不额外裁剪”，
     * 工具按未分组方式注册；非空白名单才启用活动组门禁。这样旧调用方可以无感迁移到
     * 该适配器。
     *
     * @param toolkit 请求独享的 Toolkit；不得为 {@code null}
     * @param tools 已完成 Vatica 权限/重试/审计包装的候选工具
     * @param allowedToolNames 请求允许的工具名；空集合表示全部候选工具
     * @return 注册结果（包含实际选中和未找到的允许工具名）
     */
    public static Registration register(Toolkit toolkit, AgentTool[] tools,
            Collection<String> allowedToolNames) {
        Objects.requireNonNull(toolkit, "toolkit 不能为空");
        List<AgentTool> candidates = distinctTools(tools);
        Set<String> allowed = normalizeNames(allowedToolNames);
        Set<String> registeredNames = candidates.stream()
                .map(AgentTool::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        if (allowed.isEmpty()) {
            candidates.forEach(toolkit::registerAgentTool);
            return new Registration(null, null, registeredNames, registeredNames,
                    Set.of());
        }

        String suffix = Long.toUnsignedString(GROUP_SEQUENCE.incrementAndGet(), 36);
        String allowedGroup = GROUP_PREFIX + suffix + "-allow";
        String deniedGroup = GROUP_PREFIX + suffix + "-deny";
        toolkit.createToolGroup(allowedGroup, "Vatica request allowed tools", false);
        toolkit.createToolGroup(deniedGroup, "Vatica request denied tools", false);

        Set<String> selected = new LinkedHashSet<>();
        for (AgentTool tool : candidates) {
            String group = allowed.contains(tool.getName()) ? allowedGroup : deniedGroup;
            if (group.equals(allowedGroup)) {
                selected.add(tool.getName());
            }
            toolkit.registration().agentTool(tool).group(group).apply();
        }
        // setActiveGroups also clears any default/meta groups on a reused Toolkit.
        toolkit.setActiveGroups(List.of(allowedGroup));

        Set<String> missing = new LinkedHashSet<>(allowed);
        missing.removeAll(registeredNames);
        return new Registration(allowedGroup, deniedGroup, registeredNames,
                selected, missing);
    }

    /**
     * 兼容仍自行创建 Toolkit 的调用方：只做稳定的名称筛选，不改变工具对象或包装链。
     * 空白名单返回去重后的全部候选工具。
     */
    public static AgentTool[] filter(AgentTool[] tools, Collection<String> allowedToolNames) {
        List<AgentTool> candidates = distinctTools(tools);
        Set<String> allowed = normalizeNames(allowedToolNames);
        if (allowed.isEmpty()) {
            return candidates.toArray(AgentTool[]::new);
        }
        return candidates.stream()
                .filter(tool -> allowed.contains(tool.getName()))
                .toArray(AgentTool[]::new);
    }

    private static List<AgentTool> distinctTools(AgentTool[] tools) {
        if (tools == null || tools.length == 0) {
            return List.of();
        }
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (tool == null) {
                continue;
            }
            String name = tool.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("操作失败：AgentScope 工具名不能为空。");
            }
            // 与 AgentToolCatalog 一致：重复工具名由先注册的定义优先。
            byName.putIfAbsent(name, tool);
        }
        return List.copyOf(byName.values());
    }

    private static Set<String> normalizeNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim());
            }
        }
        return immutableSet(normalized);
    }

    private static <T> Set<T> immutableSet(Collection<T> values) {
        return Collections.unmodifiableSet(values == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(values));
    }

    /** 请求级注册结果；所有集合均为不可变快照。 */
    public record Registration(String allowedGroup, String deniedGroup,
            Set<String> registeredToolNames, Set<String> selectedToolNames,
            Set<String> missingAllowedToolNames) {

        public Registration {
            registeredToolNames = immutableSet(registeredToolNames);
            selectedToolNames = immutableSet(selectedToolNames);
            missingAllowedToolNames = immutableSet(missingAllowedToolNames);
        }

        public boolean restricted() {
            return allowedGroup != null;
        }
    }
}
