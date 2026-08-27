package com.example.vatica.context;

/**
 * 迭代 31D：一次聊天请求的脱敏上下文装配状态。
 *
 * <p>该视图只暴露模式、预算和检索结果计数，不包含摘要、历史原文、用户问题或模型输出，
 * 可安全用于 SSE 和运行日志。</p>
 */
public record ChatContextStatus(ContextMode requestedMode, ContextMode effectiveMode,
        int modelWindowTokens, int plannedInputTokens, ContextDynamicBudget dynamicBudget,
        ConversationEvidenceStatus evidenceStatus, int evidenceCandidateCount, int evidenceTokens,
        boolean modeDowngraded, boolean dynamicConstrained, String historyStatus,
        int assembledHistoryTokens, int middlewareCallCount, int middlewareHistoryBudgetTokens,
        int middlewareFixedInputTokens, boolean middlewareConstrained) {

    public ChatContextStatus {
        requestedMode = ContextMode.normalize(requestedMode);
        effectiveMode = ContextMode.normalize(effectiveMode);
        modelWindowTokens = nonNegative(modelWindowTokens);
        plannedInputTokens = nonNegative(plannedInputTokens);
        dynamicBudget = dynamicBudget == null ? ContextDynamicBudget.empty() : dynamicBudget;
        evidenceStatus = evidenceStatus == null ? ConversationEvidenceStatus.UNAVAILABLE : evidenceStatus;
        evidenceCandidateCount = nonNegative(evidenceCandidateCount);
        evidenceTokens = nonNegative(evidenceTokens);
        historyStatus = historyStatus == null || historyStatus.isBlank() ? "UNAVAILABLE" : historyStatus;
        assembledHistoryTokens = nonNegative(assembledHistoryTokens);
        middlewareCallCount = nonNegative(middlewareCallCount);
        middlewareHistoryBudgetTokens = nonNegative(middlewareHistoryBudgetTokens);
        middlewareFixedInputTokens = nonNegative(middlewareFixedInputTokens);
    }

    /** 每轮 AgentScope 模型调用更新的预算账本，不附带任何 prompt 正文。 */
    public ChatContextStatus withMiddleware(ContextBudgetLedger ledger, int callCount) {
        if (ledger == null) {
            return this;
        }
        return new ChatContextStatus(requestedMode, effectiveMode, modelWindowTokens, plannedInputTokens,
                dynamicBudget, evidenceStatus, evidenceCandidateCount, evidenceTokens,
                modeDowngraded, dynamicConstrained, historyStatus, assembledHistoryTokens,
                callCount, ledger.historyBudgetTokens(), ledger.fixedInputTokens(), ledger.constrained());
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }
}
