package com.example.vatica.context;

/**
 * 迭代 31A：本次请求可按需装入的上下文材料规模。
 *
 * <p>这是候选材料的 token 估算，不是已经发送给模型的内容。规划器会先应用模式上限，
 * 再按关键事实、摘要、近期原文、历史证据、RAG 证据的优先级分配。</p>
 */
public record ContextDynamicDemand(int verifiedFactsTokens, int summaryTokens, int recentHistoryTokens,
        int historicalEvidenceTokens, int ragEvidenceTokens) {

    public ContextDynamicDemand {
        verifiedFactsTokens = nonNegative(verifiedFactsTokens);
        summaryTokens = nonNegative(summaryTokens);
        recentHistoryTokens = nonNegative(recentHistoryTokens);
        historicalEvidenceTokens = nonNegative(historicalEvidenceTokens);
        ragEvidenceTokens = nonNegative(ragEvidenceTokens);
    }

    public static ContextDynamicDemand empty() {
        return new ContextDynamicDemand(0, 0, 0, 0, 0);
    }

    public int totalTokens() {
        return saturatedAdd(saturatedAdd(verifiedFactsTokens, summaryTokens),
                saturatedAdd(recentHistoryTokens, saturatedAdd(historicalEvidenceTokens, ragEvidenceTokens)));
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
