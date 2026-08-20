package com.example.vatica.context;

import java.util.ArrayList;
import java.util.List;

import com.example.vatica.model.ConversationMessage;

/**
 * 迭代 15 I15-8：上下文裁剪——token 预算驱动，永远保留末尾 protectedFromEnd 条
 * （至少最新一条；调用方把 system prompt 与最新 user 消息放在受保护段）。
 * 超预算时从最旧内容开始丢弃，不截断任何单条消息（短期层）。
 */
public final class ContextTrimmer {

    private ContextTrimmer() {
    }

    public static List<ConversationMessage> trim(List<ConversationMessage> messages, int tokenBudget,
            int protectedFromEnd) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int keep = Math.max(1, protectedFromEnd);
        List<ConversationMessage> working = new ArrayList<>(messages);
        while (working.size() > keep && estimateTokens(working) > tokenBudget) {
            working.remove(0);
        }
        return List.copyOf(working);
    }

    public static int estimateTokens(List<ConversationMessage> messages) {
        int total = 0;
        for (ConversationMessage message : messages) {
            total += TokenEstimator.estimate(message.text());
        }
        return total;
    }
}
