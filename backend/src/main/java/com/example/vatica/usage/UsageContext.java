package com.example.vatica.usage;

import java.util.UUID;

/** 迭代 15 I15-13：一次 LLM 调用的用量上下文（请求开始 set，结束 remove；跨线程不可穿透）。 */
public final class UsageContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> LAST_USAGE_JSON = new ThreadLocal<>();

    private UsageContext() {
    }

    public record Snapshot(String requestId, String requestType, Long userId, Long orgId,
            String slotId, String taskId, Integer stepId, String reasoningMode, Integer budgetTokens,
            Integer contextFillRatio, boolean platformQuota) {
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

    public static void setLastUsageJson(String json) {
        LAST_USAGE_JSON.set(json);
    }

    /** 取走最近一次 usage JSON（聊天 SSE 收尾事件用）。 */
    public static String takeLastUsageJson() {
        String value = LAST_USAGE_JSON.get();
        LAST_USAGE_JSON.remove();
        return value;
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
