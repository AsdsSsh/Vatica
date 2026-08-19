package com.example.vatica.knowledge;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

/** 由 Spring AI EmbeddingModel 负责调用 OpenAI-compatible embedding endpoint。 */
public final class SpringAiKnowledgeEmbeddingService implements KnowledgeEmbeddingService {

    private final ObjectProvider<EmbeddingModel> models;
    private final KnowledgeProperties properties;

    public SpringAiKnowledgeEmbeddingService(ObjectProvider<EmbeddingModel> models,
            KnowledgeProperties properties) {
        this.models = models;
        this.properties = properties;
    }

    @Override
    public float[] embed(String text) {
        EmbeddingModel model = models.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("操作失败：未配置知识库 Embedding 模型，请配置 OpenAI-compatible embedding endpoint。");
        }
        float[] vector = model.embed(text);
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("操作失败：Embedding 模型返回了空向量。");
        }
        if (vector.length != properties.vectorDimensions()) {
            throw new IllegalStateException("操作失败：Embedding 维度为 " + vector.length
                    + "，但知识库配置要求 " + properties.vectorDimensions() + "。");
        }
        return vector;
    }
}
