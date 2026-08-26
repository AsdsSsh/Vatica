package com.example.vatica.context;

import java.util.Objects;

/**
 * 迭代 31A：一次模型请求的模型感知分层上下文计划。
 *
 * <p>计划只做预算分配，不负责读取数据库、生成摘要或检索 RAG。31B/31C 可依据这里的各段配额
 * 分页读取原文或筛选证据，避免把模型大窗口误当成“全量回灌”开关。</p>
 */
public record ContextAllocationPlan(ContextMode requestedMode, ContextMode effectiveMode,
        ModelCapabilityProfile modelCapability, int outputReserveTokens, int safetyMarginTokens,
        ContextFixedInput fixedInput, ContextDynamicDemand requestedDynamic,
        ContextDynamicBudget dynamicBudget, int inputCapacityTokens, int availableDynamicTokens,
        int modeDynamicCapTokens) {

    public ContextAllocationPlan {
        requestedMode = ContextMode.normalize(requestedMode);
        effectiveMode = ContextMode.normalize(effectiveMode);
        modelCapability = Objects.requireNonNull(modelCapability, "模型能力档案不能为空");
        outputReserveTokens = nonNegative(outputReserveTokens);
        safetyMarginTokens = nonNegative(safetyMarginTokens);
        fixedInput = fixedInput == null ? ContextFixedInput.empty() : fixedInput;
        requestedDynamic = requestedDynamic == null ? ContextDynamicDemand.empty() : requestedDynamic;
        dynamicBudget = dynamicBudget == null ? ContextDynamicBudget.empty() : dynamicBudget;
        inputCapacityTokens = nonNegative(inputCapacityTokens);
        availableDynamicTokens = nonNegative(availableDynamicTokens);
        modeDynamicCapTokens = nonNegative(modeDynamicCapTokens);
    }

    public int modelWindowTokens() {
        return modelCapability.contextWindowTokens();
    }

    public int modelMaxOutputTokens() {
        return modelCapability.maxOutputTokens();
    }

    public int reservedTokens() {
        return saturatedAdd(outputReserveTokens, safetyMarginTokens);
    }

    public int fixedInputTokens() {
        return fixedInput.totalTokens();
    }

    /** 模型输入区的当前规划量，不含回答输出与安全余量。 */
    public int plannedInputTokens() {
        return saturatedAdd(fixedInputTokens(), dynamicBudget.totalTokens());
    }

    /** 请求预算与输出预留后的总规划量，用于和模型窗口比较。 */
    public int plannedTotalTokens() {
        return saturatedAdd(plannedInputTokens(), reservedTokens());
    }

    public boolean modeDowngraded() {
        return requestedMode != effectiveMode;
    }

    public boolean fixedPartExceedsWindow() {
        return (long) fixedInputTokens() + reservedTokens() > modelWindowTokens();
    }

    /** 候选动态材料是否因窗口、模式策略或优先级而被裁剪。 */
    public boolean dynamicConstrained() {
        return dynamicBudget.verifiedFactsTokens() < requestedDynamic.verifiedFactsTokens()
                || dynamicBudget.summaryTokens() < requestedDynamic.summaryTokens()
                || dynamicBudget.recentHistoryTokens() < requestedDynamic.recentHistoryTokens()
                || dynamicBudget.historicalEvidenceTokens() < requestedDynamic.historicalEvidenceTokens()
                || dynamicBudget.ragEvidenceTokens() < requestedDynamic.ragEvidenceTokens();
    }

    public int unusedInputCapacityTokens() {
        long unused = (long) inputCapacityTokens - plannedInputTokens();
        return unused <= 0 ? 0 : unused >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) unused;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
