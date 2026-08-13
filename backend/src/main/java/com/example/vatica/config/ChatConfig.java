package com.example.vatica.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.vatica.controller.SessionMemory;

/**
 * 对话层装配（迭代 2.5）：绑定 {@code vatica.chat.*} 配置 + 装配会话短期记忆。
 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {

    @Bean
    SessionMemory sessionMemory(ChatProperties props) {
        return new SessionMemory(
                props.memory().maxMessages(), props.memory().maxSessions(), props.memory().maxChars());
    }
}
