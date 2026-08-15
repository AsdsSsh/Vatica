package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 默认 DeepSeek 槽位的取值来源（{@code spring.ai.openai.*}，迭代 8.5）：
 * 未在界面保存过配置时，默认槽位沿用 yml/环境变量的既有配置——
 * 界面配置（models.json）一旦存在即优先（迭代 8.5 决策：界面配置优先）。
 *
 * <p>与 Spring AI 自带的 {@code OpenAiChatProperties} 绑定同一前缀不冲突
 * （各 Bean 独立绑定），这里只取需要的 4 个键，其余键仍由框架自持。
 */
@ConfigurationProperties(prefix = "spring.ai.openai")
public record OpenAiDefaultsProperties(String apiKey, String baseUrl, Chat chat) {

    public record Chat(String model, Double temperature) {
    }
}
