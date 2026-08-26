package com.example.vatica.context;

/**
 * 迭代 31A：不允许被历史滑窗静默挤掉的输入区。
 *
 * <p>系统规则、工具 Schema、当前请求和当前任务状态先占预算；当前 Agent 回合中的工具结果、
 * 计划或审批状态可以放入 {@code currentTurnTokens}。这些值由调用方在发送模型前按实际内容估算。</p>
 */
public record ContextFixedInput(int systemPromptTokens, int toolSchemaTokens, int currentRequestTokens,
        int taskStateTokens, int currentTurnTokens) {

    public ContextFixedInput {
        systemPromptTokens = nonNegative(systemPromptTokens);
        toolSchemaTokens = nonNegative(toolSchemaTokens);
        currentRequestTokens = nonNegative(currentRequestTokens);
        taskStateTokens = nonNegative(taskStateTokens);
        currentTurnTokens = nonNegative(currentTurnTokens);
    }

    /** 与迭代 30A 的 system/tool/current 三段调用方式兼容。 */
    public ContextFixedInput(int systemPromptTokens, int toolSchemaTokens, int currentRequestTokens) {
        this(systemPromptTokens, toolSchemaTokens, currentRequestTokens, 0, 0);
    }

    public static ContextFixedInput empty() {
        return new ContextFixedInput(0, 0, 0, 0, 0);
    }

    public int totalTokens() {
        return saturatedAdd(saturatedAdd(systemPromptTokens, toolSchemaTokens),
                saturatedAdd(saturatedAdd(currentRequestTokens, taskStateTokens), currentTurnTokens));
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
