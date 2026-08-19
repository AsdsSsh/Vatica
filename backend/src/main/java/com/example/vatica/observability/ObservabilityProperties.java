package com.example.vatica.observability;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 21D：Agent 明细遥测留存策略。 */
@ConfigurationProperties(prefix = "vatica.observability")
public record ObservabilityProperties(Duration retention) {

    public static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    public ObservabilityProperties {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            retention = DEFAULT_RETENTION;
        }
    }
}
