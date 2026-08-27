package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 31C：当前会话原文证据检索的硬边界。 */
@ConfigurationProperties(prefix = "vatica.context.conversation-evidence")
public record ConversationEvidenceProperties(boolean enabled, int maxQueryChars, int maxTerms,
        int candidatesPerTerm, int maxCandidates, int maxSnippets, int maxMessageChars,
        int maxSearchMessages) {

    public static final ConversationEvidenceProperties DEFAULTS = new ConversationEvidenceProperties(
            true, 500, 8, 40, 200, 8, 1_200, 5_000);

    public ConversationEvidenceProperties {
        maxQueryChars = positive(maxQueryChars, 500);
        maxTerms = positive(maxTerms, 8);
        candidatesPerTerm = positive(candidatesPerTerm, 40);
        maxCandidates = positive(maxCandidates, 200);
        maxSnippets = positive(maxSnippets, 8);
        maxMessageChars = positive(maxMessageChars, 1_200);
        maxSearchMessages = positive(maxSearchMessages, 5_000);
    }

    public ConversationEvidenceProperties() {
        this(true, 500, 8, 40, 200, 8, 1_200, 5_000);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
