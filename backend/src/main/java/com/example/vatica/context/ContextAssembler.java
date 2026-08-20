package com.example.vatica.context;

import java.util.ArrayList;
import java.util.List;

import com.example.vatica.controller.SessionMemory;
import com.example.vatica.model.ConversationMessage;

/**
 * 迭代 15 I15-9：三层会话记忆组装——【中期摘要】+ 水位线后的近期原文，
 * 再按调用点 token 预算兜底裁剪（永远保护末尾最新一条）。
 */
public final class ContextAssembler {

    private ContextAssembler() {
    }

    public static List<ConversationMessage> chatHistory(SessionMemory memory, String sessionId, ContextBudget budget) {
        String summary = memory.summary(sessionId);
        List<ConversationMessage> recent = memory.recent(sessionId);
        List<ConversationMessage> combined = new ArrayList<>(recent.size() + 1);
        if (summary != null && !summary.isBlank()) {
            combined.add(ConversationMessage.user("【历史会话摘要】\n" + summary));
        }
        combined.addAll(recent);
        return ContextTrimmer.trim(combined, budget.tokensFor(ContextBudget.CallSite.CHAT), 1);
    }
}
