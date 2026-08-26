package com.example.vatica.controller;

/**
 * 迭代 31B：一次模型调用读取近期原文的受控请求。
 *
 * <p>热缓存仍只保留小滑窗；模型需要更多近期原文时由 JPA 按本次 token 配额读取，
 * 不把大窗口长期常驻在 JVM。{@code maxMessages} 是数据库读取护栏，不是模型上下文目标。</p>
 */
public record SessionContextReadRequest(int recentTokenBudget, int maxMessages, boolean tokenBudgeted) {

    public SessionContextReadRequest {
        recentTokenBudget = Math.max(0, recentTokenBudget);
        maxMessages = Math.max(1, maxMessages);
    }

    public SessionContextReadRequest(int recentTokenBudget, int maxMessages) {
        this(recentTokenBudget, maxMessages, true);
    }

    public static SessionContextReadRequest hotWindow(int maxMessages) {
        return new SessionContextReadRequest(0, maxMessages, false);
    }

    public boolean hasTokenBudget() {
        return tokenBudgeted;
    }
}
