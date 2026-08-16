package com.example.vatica.auth;

/** 请求身份（迭代 13 I13-2）：由 JwtAuthInterceptor 写入，工具/服务层读取。 */
public record RequestIdentity(Long userId, Long orgId, String role, String username) {
}
