package com.example.vatica.context;

/** 迭代 31C：一段带原始消息序号边界的会话证据。 */
public record ConversationEvidenceSnippet(long startSeq, long endSeq, String text, int estimatedTokens) {

    public ConversationEvidenceSnippet {
        startSeq = Math.max(0, startSeq);
        endSeq = Math.max(startSeq, endSeq);
        text = text == null ? "" : text;
        estimatedTokens = Math.max(0, estimatedTokens);
    }
}
