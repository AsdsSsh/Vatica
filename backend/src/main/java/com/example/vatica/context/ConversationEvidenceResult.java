package com.example.vatica.context;

import java.util.List;
import java.util.Optional;

import com.example.vatica.model.ConversationMessage;

/** 迭代 31C：按需原文检索结果；只向模型暴露显式的不可信数据边界。 */
public record ConversationEvidenceResult(ConversationEvidenceStatus status,
        List<ConversationEvidenceSnippet> snippets, String contextText, int estimatedTokens,
        int candidateCount) {

    public ConversationEvidenceResult {
        status = status == null ? ConversationEvidenceStatus.UNAVAILABLE : status;
        snippets = snippets == null ? List.of() : List.copyOf(snippets);
        contextText = contextText == null ? "" : contextText;
        estimatedTokens = Math.max(0, estimatedTokens);
        candidateCount = Math.max(0, candidateCount);
    }

    public static ConversationEvidenceResult empty(ConversationEvidenceStatus status) {
        return new ConversationEvidenceResult(status, List.of(), "", 0, 0);
    }

    public Optional<ConversationMessage> contextMessage() {
        return status == ConversationEvidenceStatus.MATCHED && !contextText.isBlank()
                ? Optional.of(ConversationMessage.user(contextText)) : Optional.empty();
    }
}
