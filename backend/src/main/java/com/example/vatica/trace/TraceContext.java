package com.example.vatica.trace;

import java.util.List;

/**
 * 工具调用 trace 上下文（迭代 15 I15-1）：
 * 控制器/任务执行在构建请求前写入，包装器在构建 ToolCallback 时捕获为不可变快照，
 * 后续 Spring AI 可能切换执行线程，trace 信息随快照走而不依赖 ThreadLocal 穿透。
 */
public final class TraceContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 一次推理链路的 trace 快照。
     *
     * @param traceId  本次请求/步骤的 trace id
     * @param channel  chat:{userId}:{sessionId} 或 task:{userId}:{taskId}
     * @param taskId   任务 id（聊天为 null）
     * @param stepId   任务步骤 id（聊天为 null）
     * @param userId   租户归属（agent_trace 查询隔离用）
     * @param orgId    组织归属
     * @param persist  true=落 agent_trace 表（任务）；false=仅 SSE 可见（聊天）
     */
    public record Snapshot(String traceId, String channel, String taskId, Integer stepId,
            Long userId, Long orgId, boolean persist, String agentId, String role,
            String skillId, String skillVersion, List<String> skillPermissions) {
        public Snapshot {
            skillPermissions = skillPermissions == null ? List.of() : List.copyOf(skillPermissions);
        }

        /** 旧聊天/测试构造器兼容：无任务角色时保持 null。 */
        public Snapshot(String traceId, String channel, String taskId, Integer stepId,
                Long userId, Long orgId, boolean persist) {
            this(traceId, channel, taskId, stepId, userId, orgId, persist,
                    null, null, null, null, List.of());
        }

        /** 迭代 17C 构造器兼容：没有 Skill 时审计字段为空。 */
        public Snapshot(String traceId, String channel, String taskId, Integer stepId,
                Long userId, Long orgId, boolean persist, String agentId, String role) {
            this(traceId, channel, taskId, stepId, userId, orgId, persist,
                    agentId, role, null, null, List.of());
        }
    }

    public static void set(Snapshot snapshot) {
        CURRENT.set(snapshot);
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 在指定快照下执行动作，结束后恢复原上下文（虚拟线程复用时不串 trace）。 */
    public static <T> T callWith(Snapshot snapshot, java.util.function.Supplier<T> action) {
        Snapshot previous = CURRENT.get();
        set(snapshot);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }
}
