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

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
