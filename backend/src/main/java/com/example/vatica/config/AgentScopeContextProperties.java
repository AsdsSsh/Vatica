package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 30A：AgentScope 上下文预算护栏。 */
@ConfigurationProperties(prefix = "vatica.agent.context")
public record AgentScopeContextProperties(Boolean enabled, int outputReserveTokens,
        int safetyMarginTokens, int fallbackModelWindowTokens) {

    public static final int DEFAULT_OUTPUT_RESERVE_TOKENS = 2_048;
    public static final int DEFAULT_SAFETY_MARGIN_TOKENS = 512;
    public static final int DEFAULT_FALLBACK_MODEL_WINDOW_TOKENS = 16_000;

    public AgentScopeContextProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        outputReserveTokens = outputReserveTokens <= 0
                ? DEFAULT_OUTPUT_RESERVE_TOKENS : outputReserveTokens;
        safetyMarginTokens = safetyMarginTokens <= 0
                ? DEFAULT_SAFETY_MARGIN_TOKENS : safetyMarginTokens;
        fallbackModelWindowTokens = fallbackModelWindowTokens <= 0
                ? DEFAULT_FALLBACK_MODEL_WINDOW_TOKENS : fallbackModelWindowTokens;
    }

    public boolean enabledValue() {
        return Boolean.TRUE.equals(enabled);
    }
}
