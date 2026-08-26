package com.example.vatica.context;

/** 迭代 31A：分层动态材料在本轮真正可使用的 token 配额。 */
public record ContextDynamicBudget(int verifiedFactsTokens, int summaryTokens, int recentHistoryTokens,
        int historicalEvidenceTokens, int ragEvidenceTokens) {

    public ContextDynamicBudget {
        verifiedFactsTokens = nonNegative(verifiedFactsTokens);
        summaryTokens = nonNegative(summaryTokens);
        recentHistoryTokens = nonNegative(recentHistoryTokens);
        historicalEvidenceTokens = nonNegative(historicalEvidenceTokens);
        ragEvidenceTokens = nonNegative(ragEvidenceTokens);
    }

    public static ContextDynamicBudget empty() {
        return new ContextDynamicBudget(0, 0, 0, 0, 0);
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
