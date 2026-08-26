package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.vatica.config.ContextAllocationProperties;

/** 迭代 31A：模型能力与分层上下文预算的纯计算验证。 */
class ContextAllocationPlannerTest {

    private final ContextAllocationPlanner planner = new ContextAllocationPlanner(new ContextAllocationProperties());

    @Test
    void normalModeKeepsConservativeBudgetForUnknownModel() {
        ContextAllocationPlan plan = planner.plan(ModelCapabilityProfile.unknown("private-endpoint"),
                ContextMode.NORMAL, new ContextFixedInput(1_000, 2_000, 500),
                new ContextDynamicDemand(4_000, 4_000, 20_000, 10_000, 10_000));

        assertThat(plan.modelWindowTokens()).isEqualTo(16_000);
        assertThat(plan.effectiveMode()).isEqualTo(ContextMode.NORMAL);
        assertThat(plan.modeDowngraded()).isFalse();
        assertThat(plan.outputReserveTokens()).isEqualTo(2_048);
        assertThat(plan.safetyMarginTokens()).isEqualTo(512);
        assertThat(plan.fixedInputTokens()).isEqualTo(3_500);
        assertThat(plan.plannedTotalTokens()).isLessThanOrEqualTo(16_000);
        assertThat(plan.dynamicConstrained()).isTrue();
    }

    @Test
    void longTaskUsesWindowAwareBudgetFor256kModel() {
        ModelCapabilityProfile model = new ModelCapabilityProfile("model-256k", 256_000,
                16_000, "provider-tokenizer-v1", true);
        ContextDynamicDemand demand = new ContextDynamicDemand(12_000, 20_000, 48_000, 64_000, 32_000);

        ContextAllocationPlan plan = planner.plan(model, ContextMode.LONG_TASK,
                new ContextFixedInput(6_000, 10_000, 4_000, 8_000, 4_000), demand);

        assertThat(plan.effectiveMode()).isEqualTo(ContextMode.LONG_TASK);
        assertThat(plan.modelWindowTokens()).isEqualTo(256_000);
        assertThat(plan.outputReserveTokens()).isEqualTo(16_000);
        assertThat(plan.safetyMarginTokens()).isEqualTo(4_096);
        assertThat(plan.availableDynamicTokens()).isEqualTo(203_904);
        assertThat(plan.dynamicBudget().verifiedFactsTokens()).isEqualTo(12_000);
        assertThat(plan.dynamicBudget().summaryTokens()).isEqualTo(20_000);
        assertThat(plan.dynamicBudget().recentHistoryTokens()).isEqualTo(48_000);
        assertThat(plan.dynamicBudget().historicalEvidenceTokens()).isEqualTo(64_000);
        assertThat(plan.modeDynamicCapTokens()).isEqualTo(166_400);
        assertThat(plan.dynamicBudget().ragEvidenceTokens()).isEqualTo(22_400);
        assertThat(plan.dynamicConstrained()).isTrue();
        assertThat(plan.plannedTotalTokens()).isLessThanOrEqualTo(256_000);
    }

    @Test
    void deepReviewDowngradesToNormalWhenModelWindowIsTooSmallForLongTask() {
        ModelCapabilityProfile model = new ModelCapabilityProfile("small", 64_000, 8_000,
                ModelCapabilityProfile.ESTIMATED_TOKENIZER, true);

        ContextAllocationPlan plan = planner.plan(model, ContextMode.DEEP_REVIEW, ContextFixedInput.empty(),
                new ContextDynamicDemand(4_000, 8_000, 16_000, 32_000, 16_000));

        assertThat(plan.requestedMode()).isEqualTo(ContextMode.DEEP_REVIEW);
        assertThat(plan.effectiveMode()).isEqualTo(ContextMode.NORMAL);
        assertThat(plan.modeDowngraded()).isTrue();
        assertThat(plan.dynamicBudget().totalTokens()).isLessThanOrEqualTo(plan.availableDynamicTokens());
    }

    @Test
    void deepReviewDowngradesToLongTaskForA256kModel() {
        ContextAllocationPlan plan = planner.plan(new ModelCapabilityProfile("model-256k", 256_000,
                16_000, "provider-tokenizer-v1", true), ContextMode.DEEP_REVIEW, ContextFixedInput.empty());

        assertThat(plan.requestedMode()).isEqualTo(ContextMode.DEEP_REVIEW);
        assertThat(plan.effectiveMode()).isEqualTo(ContextMode.LONG_TASK);
        assertThat(plan.modeDowngraded()).isTrue();
        assertThat(plan.modeDynamicCapTokens()).isEqualTo(166_400);
    }

    @Test
    void deepReviewUsesConfiguredCeilingForOneMillionTokenModel() {
        ModelCapabilityProfile model = new ModelCapabilityProfile("model-1m", 1_000_000, 65_536,
                "provider-tokenizer-v2", true);

        ContextAllocationPlan plan = planner.plan(model, ContextMode.DEEP_REVIEW,
                new ContextFixedInput(8_000, 12_000, 4_000, 8_000, 4_000));

        assertThat(plan.effectiveMode()).isEqualTo(ContextMode.DEEP_REVIEW);
        assertThat(plan.outputReserveTokens()).isEqualTo(65_536);
        assertThat(plan.safetyMarginTokens()).isEqualTo(32_768);
        assertThat(plan.modeDynamicCapTokens()).isEqualTo(768_000);
        assertThat(plan.dynamicBudget().totalTokens()).isEqualTo(768_000);
        assertThat(plan.plannedTotalTokens()).isLessThanOrEqualTo(1_000_000);
    }

    @Test
    void normalModeDoesNotFillA256kWindowWithHistoryByDefault() {
        ContextAllocationPlan plan = planner.plan(new ModelCapabilityProfile("model-256k", 256_000,
                16_000, "provider-tokenizer-v1", true), ContextMode.NORMAL, ContextFixedInput.empty());

        assertThat(plan.modeDynamicCapTokens()).isEqualTo(64_000);
        assertThat(plan.dynamicBudget().totalTokens()).isEqualTo(64_000);
    }

    @Test
    void fixedInputAndReserveCanExhaustWindowWithoutNegativeBudgets() {
        ModelCapabilityProfile model = new ModelCapabilityProfile("tiny", 1_024, 800,
                ModelCapabilityProfile.ESTIMATED_TOKENIZER, true);

        ContextAllocationPlan plan = planner.plan(model, ContextMode.NORMAL,
                new ContextFixedInput(900, 900, 900, 900, 900),
                new ContextDynamicDemand(10_000, 10_000, 10_000, 10_000, 10_000));

        assertThat(plan.fixedInputTokens()).isEqualTo(4_500);
        assertThat(plan.inputCapacityTokens()).isZero();
        assertThat(plan.availableDynamicTokens()).isZero();
        assertThat(plan.dynamicBudget()).isEqualTo(ContextDynamicBudget.empty());
        assertThat(plan.fixedPartExceedsWindow()).isTrue();
        assertThat(plan.plannedTotalTokens()).isEqualTo(4_500 + plan.reservedTokens());
    }

    @Test
    void configuredCapabilityOverridesDiscoveredWindowAndOutput() {
        ContextAllocationProperties properties = new ContextAllocationProperties(16_000, 2_048,
                null, null, null,
                Map.of("custom-model", new ModelCapabilityProfile("ignored", 256_000, 32_000,
                        "custom-tokenizer", true)));

        ModelCapabilityProfile resolved = properties.resolveCapability(
                new ModelCapabilityProfile("CUSTOM-MODEL", 32_000, 4_000,
                        ModelCapabilityProfile.ESTIMATED_TOKENIZER, false));

        assertThat(resolved.contextWindowTokens()).isEqualTo(256_000);
        assertThat(resolved.maxOutputTokens()).isEqualTo(32_000);
        assertThat(resolved.tokenizerId()).isEqualTo("custom-tokenizer");
        assertThat(resolved.capabilityVerified()).isTrue();
    }
}
