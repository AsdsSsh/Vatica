package com.example.vatica.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 15 I15-13：用量与配额配置（{@code vatica.usage.*}）。 */
@ConfigurationProperties(prefix = "vatica.usage")
public record UsageProperties(long dailyTokenQuota) {

    public static final long DEFAULT_DAILY_TOKEN_QUOTA = 2_000_000;

    public UsageProperties {
        if (dailyTokenQuota <= 0) {
            dailyTokenQuota = DEFAULT_DAILY_TOKEN_QUOTA;
        }
    }
}
