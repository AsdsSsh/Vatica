package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 默认 DeepSeek 槽位的取值来源（{@code vatica.model.openai.*}，迭代 22D）：
 * 未在界面保存过配置时，默认槽位沿用 yml/环境变量的既有配置——
 * 界面配置（models.json）一旦存在即优先（迭代 8.5 决策：界面配置优先）。
 *
 * <p>该配置只描述 Vatica 默认模型槽位，不再由其他 AI 框架自动装配。
 */
@ConfigurationProperties(prefix = "vatica.model.openai")
public record OpenAiDefaultsProperties(String apiKey, String baseUrl, Chat chat) {

    public record Chat(String model, Double temperature) {
    }
}
