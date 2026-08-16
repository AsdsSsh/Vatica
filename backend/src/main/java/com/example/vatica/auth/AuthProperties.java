package com.example.vatica.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 鉴权配置（迭代 13 I13-2）：{@code vatica.auth.*}。
 * 前端登录页落地前 enabled 默认 false（本地开发不破坏既有 UI），
 * 云端部署时在 yml 打开。
 */
@ConfigurationProperties(prefix = "vatica.auth")
public record AuthProperties(boolean enabled, Duration tokenTtl) {

    public AuthProperties {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            tokenTtl = Duration.ofHours(12);
        }
    }
}
