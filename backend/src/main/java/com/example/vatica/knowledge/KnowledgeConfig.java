package com.example.vatica.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 知识库装配；默认 local-hash 可离线运行，生产可替换 EmbeddingGateway Bean。 */
@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfig {

    @Bean
    @ConditionalOnMissingBean(EmbeddingGateway.class)
    @ConditionalOnProperty(prefix = "vatica.knowledge", name = "embedding-provider", havingValue = "local-hash",
            matchIfMissing = true)
    EmbeddingGateway localKnowledgeEmbeddingModel(KnowledgeProperties properties) {
        return new LocalHashEmbeddingModel(properties.vectorDimensions());
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingGateway.class)
    @ConditionalOnProperty(prefix = "vatica.knowledge", name = "embedding-provider", havingValue = "openai")
    EmbeddingGateway openAiKnowledgeEmbeddingModel(KnowledgeProperties properties, ObjectMapper mapper) {
        return new OpenAiEmbeddingGateway(properties.openai(), properties.vectorDimensions(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeEmbeddingService.class)
    KnowledgeEmbeddingService knowledgeEmbeddingService(EmbeddingGateway gateway, KnowledgeProperties properties) {
        if (gateway.dimensions() != properties.vectorDimensions()) {
            throw new IllegalStateException("操作失败：Embedding 维度为 " + gateway.dimensions()
                    + "，但知识库配置要求 " + properties.vectorDimensions() + "。");
        }
        return gateway;
    }
}
