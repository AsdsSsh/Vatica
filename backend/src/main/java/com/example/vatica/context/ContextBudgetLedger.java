package com.example.vatica.context;

/**
 * 迭代 30A：一次 AgentScope 模型调用的输入预算账本。
 *
 * <p>历史消息不再独占预算。系统提示、工具 Schema、当前请求、输出预留和安全余量
 * 先占位，剩余空间才交给会话历史或 Agent 内部上下文。</p>
 */
public record ContextBudgetLedger(ContextBudget.CallSite callSite, int modelWindowTokens,
        int requestedHistoryTokens, int outputReserveTokens, int safetyMarginTokens,
        int systemPromptTokens, int toolSchemaTokens, int currentRequestTokens,
        int historyBudgetTokens) {

    public ContextBudgetLedger {
        callSite = callSite == null ? ContextBudget.CallSite.CHAT : callSite;
        modelWindowTokens = nonNegative(modelWindowTokens);
        requestedHistoryTokens = nonNegative(requestedHistoryTokens);
        outputReserveTokens = nonNegative(outputReserveTokens);
        safetyMarginTokens = nonNegative(safetyMarginTokens);
        systemPromptTokens = nonNegative(systemPromptTokens);
        toolSchemaTokens = nonNegative(toolSchemaTokens);
        currentRequestTokens = nonNegative(currentRequestTokens);
        historyBudgetTokens = nonNegative(historyBudgetTokens);
    }

    public int fixedInputTokens() {
        return saturatedAdd(saturatedAdd(systemPromptTokens, toolSchemaTokens), currentRequestTokens);
    }

    public int estimatedInputTokens(int historyTokens) {
        return saturatedAdd(fixedInputTokens(), Math.max(0, historyTokens));
    }

    public int reservedTokens() {
        return saturatedAdd(outputReserveTokens, safetyMarginTokens);
    }

    public boolean constrained() {
        return historyBudgetTokens < requestedHistoryTokens;
    }

    public boolean fixedPartExceedsWindow() {
        return (long) fixedInputTokens() + reservedTokens() > modelWindowTokens;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
