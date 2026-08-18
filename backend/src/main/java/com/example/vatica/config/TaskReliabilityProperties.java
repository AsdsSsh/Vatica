package com.example.vatica.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 迭代 18：任务执行可靠性边界。
 *
 * <p>超时只收口当前编排波次，不会把一次不可逆的工具调用伪装成可安全重放；
 * 服务重启后的恢复仍要求用户确认中断步骤，避免副作用静默重复。</p>
 */
@ConfigurationProperties(prefix = "vatica.task")
public record TaskReliabilityProperties(Duration stepTimeout) {

    public static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofMinutes(5);

    public TaskReliabilityProperties {
        if (stepTimeout == null || stepTimeout.isZero() || stepTimeout.isNegative()) {
            stepTimeout = DEFAULT_STEP_TIMEOUT;
        }
    }
}
