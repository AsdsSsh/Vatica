package com.example.vatica.context;

/**
 * 迭代 15 I15-8：各调用点输入 token 预算（可配置；当前用已定稿默认值）。
 * chat 16k / planner 8k / executor 12k每步 / judge 16k / summarizer 8k。
 */
public record ContextBudget(int chatTokens, int plannerTokens, int executorTokens,
        int judgeTokens, int summarizerTokens) {

    public static final int DEFAULT_CHAT_TOKENS = 16_000;
    public static final int DEFAULT_PLANNER_TOKENS = 8_000;
    public static final int DEFAULT_EXECUTOR_TOKENS = 12_000;
    public static final int DEFAULT_JUDGE_TOKENS = 16_000;
    public static final int DEFAULT_SUMMARIZER_TOKENS = 8_000;

    public ContextBudget {
        chatTokens = positive(chatTokens, DEFAULT_CHAT_TOKENS);
        plannerTokens = positive(plannerTokens, DEFAULT_PLANNER_TOKENS);
        executorTokens = positive(executorTokens, DEFAULT_EXECUTOR_TOKENS);
        judgeTokens = positive(judgeTokens, DEFAULT_JUDGE_TOKENS);
        summarizerTokens = positive(summarizerTokens, DEFAULT_SUMMARIZER_TOKENS);
    }

    public enum CallSite {
        CHAT, PLANNER, EXECUTOR, JUDGE, SUMMARIZER
    }

    public int tokensFor(CallSite callSite) {
        return switch (callSite) {
            case CHAT -> chatTokens;
            case PLANNER -> plannerTokens;
            case EXECUTOR -> executorTokens;
            case JUDGE -> judgeTokens;
            case SUMMARIZER -> summarizerTokens;
        };
    }

    /**
     * 迭代 30A：只替换一个调用点的有效历史预算，保留其它角色预算不变。
     * 这样 AgentScope 的窗口计算不会把 Planner/Judge 的策略意外改小。
     */
    public ContextBudget with(CallSite callSite, int tokens) {
        int value = Math.max(1, tokens);
        return switch (callSite) {
            case CHAT -> new ContextBudget(value, plannerTokens, executorTokens, judgeTokens, summarizerTokens);
            case PLANNER -> new ContextBudget(chatTokens, value, executorTokens, judgeTokens, summarizerTokens);
            case EXECUTOR -> new ContextBudget(chatTokens, plannerTokens, value, judgeTokens, summarizerTokens);
            case JUDGE -> new ContextBudget(chatTokens, plannerTokens, executorTokens, value, summarizerTokens);
            case SUMMARIZER -> new ContextBudget(chatTokens, plannerTokens, executorTokens, judgeTokens, value);
        };
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
