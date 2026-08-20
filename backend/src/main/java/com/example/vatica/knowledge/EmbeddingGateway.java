package com.example.vatica.knowledge;

/** 迭代 22D：知识库向量化边界，不依赖聊天或模型框架。 */
public interface EmbeddingGateway extends KnowledgeEmbeddingService {

    int dimensions();
}
