package com.example.vatica.context;

/**
 * 迭代 31A：模型上下文能力的不可变快照。
 *
 * <p>能力档案只描述模型可接受的窗口和最大输出，不表示本次请求应该填满窗口。
 * 未知端点可以把窗口和输出设为 {@code 0}，再由 {@link com.example.vatica.config.ContextAllocationProperties}
 * 使用保守回退值补齐。</p>
 */
public record ModelCapabilityProfile(String modelId, int contextWindowTokens, int maxOutputTokens,
        String tokenizerId, boolean capabilityVerified) {

    public static final String ESTIMATED_TOKENIZER = "estimate-v1";

    public ModelCapabilityProfile {
        modelId = modelId == null ? "" : modelId.trim();
        contextWindowTokens = nonNegative(contextWindowTokens);
        maxOutputTokens = nonNegative(maxOutputTokens);
        if (contextWindowTokens > 0 && maxOutputTokens > contextWindowTokens) {
            maxOutputTokens = contextWindowTokens;
        }
        tokenizerId = tokenizerId == null || tokenizerId.isBlank() ? ESTIMATED_TOKENIZER : tokenizerId.trim();
    }

    public static ModelCapabilityProfile unknown(String modelId) {
        return new ModelCapabilityProfile(modelId, 0, 0, ESTIMATED_TOKENIZER, false);
    }

    public boolean hasKnownContextWindow() {
        return contextWindowTokens > 0;
    }

    public boolean hasKnownMaxOutput() {
        return maxOutputTokens > 0;
    }

    public ModelCapabilityProfile withModelId(String value) {
        return new ModelCapabilityProfile(value, contextWindowTokens, maxOutputTokens, tokenizerId,
                capabilityVerified);
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }
}
