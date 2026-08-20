package com.example.vatica.model;

/** 迭代 22A：厂商无关的单次模型用量快照。 */
public record ModelUsage(int inputTokens, int outputTokens, int totalTokens, int cachedTokens) {

    public ModelUsage {
        inputTokens = Math.max(0, inputTokens);
        outputTokens = Math.max(0, outputTokens);
        totalTokens = Math.max(inputTokens + outputTokens, totalTokens);
        cachedTokens = Math.max(0, cachedTokens);
    }

    public static ModelUsage empty() {
        return new ModelUsage(0, 0, 0, 0);
    }
}
