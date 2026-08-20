package com.example.vatica.knowledge;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 19B：知识库索引与检索边界。 */
@ConfigurationProperties(prefix = "vatica.knowledge")
public record KnowledgeProperties(boolean enabled, String embeddingProvider, int vectorDimensions, int maxDocumentBytes,
        int chunkSize, int chunkOverlap, int maxSearchChars, OpenAi openai) {

    public KnowledgeProperties {
        embeddingProvider = embeddingProvider == null || embeddingProvider.isBlank()
                ? "local-hash" : embeddingProvider.trim().toLowerCase(Locale.ROOT);
        if (!(embeddingProvider.equals("local-hash") || embeddingProvider.equals("openai"))) {
            throw new IllegalArgumentException("vatica.knowledge.embedding-provider 仅支持 local-hash / openai。");
        }
        if (vectorDimensions < 1) {
            vectorDimensions = 1536;
        }
        if (maxDocumentBytes < 1024) {
            maxDocumentBytes = 5 * 1024 * 1024;
        }
        if (chunkSize < 100) {
            chunkSize = 800;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            chunkOverlap = Math.min(120, chunkSize / 4);
        }
        if (maxSearchChars < 1000) {
            maxSearchChars = 6000;
        }
        if (openai == null) {
            openai = new OpenAi(null, null, null);
        }
    }

    /** OpenAI-compatible embedding endpoint；仅 embedding-provider=openai 时使用。 */
    public record OpenAi(String baseUrl, String apiKey, String model) {
        public OpenAi {
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            model = model == null ? "" : model.trim();
        }
    }
}
