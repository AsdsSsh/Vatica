package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.vatica.config.ContextAllocationProperties;

/** 迭代 31A：默认模式策略和异常配置的安全回退。 */
class ContextAllocationPropertiesTest {

    @Test
    void defaultsExposeThreeProgressiveModePolicies() {
        ContextAllocationProperties properties = new ContextAllocationProperties();

        assertThat(properties.policyFor(ContextMode.NORMAL).minimumModelWindowTokens()).isEqualTo(16_000);
        assertThat(properties.policyFor(ContextMode.LONG_TASK).minimumModelWindowTokens()).isEqualTo(128_000);
        assertThat(properties.policyFor(ContextMode.DEEP_REVIEW).minimumModelWindowTokens()).isEqualTo(512_000);
        assertThat(properties.policyFor(ContextMode.NORMAL).maximumWindowPercent()).isEqualTo(25);
        assertThat(properties.policyFor(ContextMode.LONG_TASK).maximumWindowPercent()).isEqualTo(65);
        assertThat(properties.policyFor(ContextMode.DEEP_REVIEW).maximumWindowPercent()).isEqualTo(80);
        assertThat(properties.policyFor(null)).isEqualTo(properties.normal());
        assertThat(properties.longTask().dynamicBlockTokens()).isEqualTo(256_000);
        assertThat(properties.deepReview().dynamicBlockTokens()).isEqualTo(768_000);
    }

    @Test
    void nonPositiveTopLevelValuesUseSafeFallbacks() {
        ContextAllocationProperties properties = new ContextAllocationProperties(-1, -1,
                null, null, null, null);

        assertThat(properties.fallbackModelWindowTokens()).isEqualTo(16_000);
        assertThat(properties.fallbackMaxOutputTokens()).isEqualTo(2_048);
        assertThat(properties.modelCapabilities()).isEmpty();
    }

    @Test
    void capabilityProfileClipsOutputToWindow() {
        ModelCapabilityProfile profile = new ModelCapabilityProfile("small", 1_000, 9_000,
                "", false);

        assertThat(profile.maxOutputTokens()).isEqualTo(1_000);
        assertThat(profile.tokenizerId()).isEqualTo(ModelCapabilityProfile.ESTIMATED_TOKENIZER);
    }
}
