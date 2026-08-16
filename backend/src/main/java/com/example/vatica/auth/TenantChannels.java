package com.example.vatica.auth;

/** 迭代 14：SSE、权限等待与临时授权统一使用带用户前缀的租户频道。 */
public final class TenantChannels {

    private TenantChannels() {
    }

    public static String chat(RequestIdentity identity, String sessionId) {
        String normalized = sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
        return "user:" + identity.userId() + ":chat:" + normalized;
    }

    public static String task(RequestIdentity identity, String taskId) {
        return "user:" + identity.userId() + ":task:" + taskId;
    }
}
