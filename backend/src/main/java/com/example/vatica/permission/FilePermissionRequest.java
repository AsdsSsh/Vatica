package com.example.vatica.permission;

import java.time.Instant;

/** 一次文件权限请求（迭代 11）：经 SSE 发给前端，由用户批准/拒绝。 */
public record FilePermissionRequest(
        String requestId,
        String channel,
        String path,
        FileAccess access,
        FilePermissionMode mode,
        String description,
        Instant createdAt) {
}
