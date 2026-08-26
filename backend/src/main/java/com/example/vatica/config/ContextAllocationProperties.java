package com.example.vatica.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.vatica.context.ContextMode;
import com.example.vatica.context.ModelCapabilityProfile;

/**
 * 迭代 31A：模型感知的分层长上下文策略（{@code vatica.context.allocation.*}）。
 *
 * <p>每个模式同时声明适用的最小模型窗口、回答和安全预留，以及动态上下文各层的上限。
 * 自定义 OpenAI 兼容端点可在 {@code model-capabilities} 显式声明窗口和最大输出；未知端点
 * 仍然使用保守回退能力，不能因名字像大模型而自动放大历史。</p>
 */
@ConfigurationProperties(prefix = "vatica.context.allocation")
public record ContextAllocationProperties(int fallbackModelWindowTokens, int fallbackMaxOutputTokens,
        ModePolicy normal, ModePolicy longTask, ModePolicy deepReview,
        Map<String, ModelCapabilityProfile> modelCapabilities) {

    public static final int DEFAULT_FALLBACK_MODEL_WINDOW_TOKENS = 16_000;
    public static final int DEFAULT_FALLBACK_MAX_OUTPUT_TOKENS = 2_048;

    public static final ModePolicy DEFAULT_NORMAL = new ModePolicy(
            16_000, 64_000, 25, 2_048, 512,
            8_000, 12_000, 40_000, 20_000, 16_000);
    public static final ModePolicy DEFAULT_LONG_TASK = new ModePolicy(
            128_000, 192_000, 65, 16_000, 4_096,
            16_000, 32_000, 72_000, 80_000, 56_000);
    public static final ModePolicy DEFAULT_DEEP_REVIEW = new ModePolicy(
            512_000, 768_000, 80, 65_536, 32_768,
            24_000, 64_000, 160_000, 320_000, 200_000);

    public ContextAllocationProperties {
        fallbackModelWindowTokens = positive(fallbackModelWindowTokens, DEFAULT_FALLBACK_MODEL_WINDOW_TOKENS);
        fallbackMaxOutputTokens = positive(fallbackMaxOutputTokens, DEFAULT_FALLBACK_MAX_OUTPUT_TOKENS);
        if (fallbackMaxOutputTokens > fallbackModelWindowTokens) {
            fallbackMaxOutputTokens = fallbackModelWindowTokens;
        }
        normal = merge(normal, DEFAULT_NORMAL);
        longTask = merge(longTask, DEFAULT_LONG_TASK);
        deepReview = merge(deepReview, DEFAULT_DEEP_REVIEW);
        modelCapabilities = normalizedCapabilities(modelCapabilities);
    }

    public ContextAllocationProperties() {
        this(0, 0, null, null, null, Map.of());
    }

    public ModePolicy policyFor(ContextMode mode) {
        return switch (ContextMode.normalize(mode)) {
            case NORMAL -> normal;
            case LONG_TASK -> longTask;
            case DEEP_REVIEW -> deepReview;
        };
    }

    /**
     * 合并运行时发现能力与人工配置。人工档案优先，发现值次之，最后才使用安全回退值。
     */
    public ModelCapabilityProfile resolveCapability(ModelCapabilityProfile discovered) {
        ModelCapabilityProfile source = discovered == null ? ModelCapabilityProfile.unknown("") : discovered;
        ModelCapabilityProfile configured = modelCapabilities.get(normalizeKey(source.modelId()));
        int window = firstPositive(configured == null ? 0 : configured.contextWindowTokens(),
                source.contextWindowTokens(), fallbackModelWindowTokens);
        int maxOutput = firstPositive(configured == null ? 0 : configured.maxOutputTokens(),
                source.maxOutputTokens(), fallbackMaxOutputTokens);
        maxOutput = Math.min(maxOutput, window);
        String tokenizer = configured == null ? source.tokenizerId() : configured.tokenizerId();
        boolean verified = configured != null || source.capabilityVerified();
        return new ModelCapabilityProfile(source.modelId(), window, maxOutput, tokenizer, verified);
    }

    public ModelCapabilityProfile resolveCapability(String modelId, int discoveredWindowTokens,
            int discoveredMaxOutputTokens) {
        return resolveCapability(new ModelCapabilityProfile(modelId, discoveredWindowTokens,
                discoveredMaxOutputTokens, ModelCapabilityProfile.ESTIMATED_TOKENIZER,
                discoveredWindowTokens > 0 || discoveredMaxOutputTokens > 0));
    }

    /** 一种模式下动态上下文块的目标上限。 */
    public record ModePolicy(int minimumModelWindowTokens, int maximumDynamicTokens,
            int maximumWindowPercent, int outputReserveTokens, int safetyMarginTokens, int verifiedFactsTokens,
            int summaryTokens, int recentHistoryTokens, int historicalEvidenceTokens,
            int ragEvidenceTokens) {

        public ModePolicy {
            minimumModelWindowTokens = nonNegative(minimumModelWindowTokens);
            maximumDynamicTokens = nonNegative(maximumDynamicTokens);
            maximumWindowPercent = Math.clamp(maximumWindowPercent, 0, 100);
            outputReserveTokens = nonNegative(outputReserveTokens);
            safetyMarginTokens = nonNegative(safetyMarginTokens);
            verifiedFactsTokens = nonNegative(verifiedFactsTokens);
            summaryTokens = nonNegative(summaryTokens);
            recentHistoryTokens = nonNegative(recentHistoryTokens);
            historicalEvidenceTokens = nonNegative(historicalEvidenceTokens);
            ragEvidenceTokens = nonNegative(ragEvidenceTokens);
        }

        public int dynamicBlockTokens() {
            return saturatedAdd(saturatedAdd(verifiedFactsTokens, summaryTokens),
                    saturatedAdd(recentHistoryTokens,
                            saturatedAdd(historicalEvidenceTokens, ragEvidenceTokens)));
        }

        /** 防止大窗口模式把整个模型窗口默认交给历史材料。 */
        public int windowScopedDynamicTokens(int modelWindowTokens) {
            long value = (long) Math.max(0, modelWindowTokens) * maximumWindowPercent / 100;
            return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
    }

    private static ModePolicy merge(ModePolicy configured, ModePolicy defaults) {
        if (configured == null) {
            return defaults;
        }
        return new ModePolicy(
                positive(configured.minimumModelWindowTokens(), defaults.minimumModelWindowTokens()),
                positive(configured.maximumDynamicTokens(), defaults.maximumDynamicTokens()),
                positive(configured.maximumWindowPercent(), defaults.maximumWindowPercent()),
                positive(configured.outputReserveTokens(), defaults.outputReserveTokens()),
                positive(configured.safetyMarginTokens(), defaults.safetyMarginTokens()),
                positive(configured.verifiedFactsTokens(), defaults.verifiedFactsTokens()),
                positive(configured.summaryTokens(), defaults.summaryTokens()),
                positive(configured.recentHistoryTokens(), defaults.recentHistoryTokens()),
                positive(configured.historicalEvidenceTokens(), defaults.historicalEvidenceTokens()),
                positive(configured.ragEvidenceTokens(), defaults.ragEvidenceTokens()));
    }

    private static Map<String, ModelCapabilityProfile> normalizedCapabilities(
            Map<String, ModelCapabilityProfile> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, ModelCapabilityProfile> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = normalizeKey(key);
            if (!normalizedKey.isEmpty() && value != null) {
                normalized.put(normalizedKey, value.withModelId(key));
            }
        });
        return Map.copyOf(normalized);
    }

    private static int firstPositive(int first, int second, int fallback) {
        return first > 0 ? first : second > 0 ? second : fallback;
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
