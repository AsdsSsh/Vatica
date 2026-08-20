package com.example.vatica.model;

import java.util.List;
import java.util.Objects;

import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.ReasoningMode;

/** 迭代 22A：Vatica 交给模型层的只读调用快照。 */
public record ModelInvocation(ModelSlot slot, String systemPrompt, List<ConversationMessage> history,
        String userPrompt, ReasoningMode reasoningMode) {

    public ModelInvocation {
        slot = Objects.requireNonNull(slot, "slot");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        history = history == null ? List.of() : List.copyOf(history);
        userPrompt = userPrompt == null ? "" : userPrompt;
        reasoningMode = reasoningMode == null ? ReasoningMode.DISABLED : reasoningMode;
    }
}
