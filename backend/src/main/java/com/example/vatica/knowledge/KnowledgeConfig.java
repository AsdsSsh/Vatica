package com.example.vatica.knowledge;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 知识库装配；测试可以替换 KnowledgeEmbeddingService，避免访问外部模型。 */
@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfig {

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(prefix = "vatica.knowledge", name = "embedding-provider",
            havingValue = "local-hash", matchIfMissing = true)
    EmbeddingModel localKnowledgeEmbeddingModel(KnowledgeProperties properties) {
        return new LocalHashEmbeddingModel(properties.vectorDimensions());
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeEmbeddingService.class)
    KnowledgeEmbeddingService knowledgeEmbeddingService(ObjectProvider<EmbeddingModel> models,
            KnowledgeProperties properties) {
        return new SpringAiKnowledgeEmbeddingService(models, properties);
    }
}
