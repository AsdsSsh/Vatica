package com.example.vatica.config;

import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 动态委托的 ChatClient（迭代 8.5）：默认模型/规划/评测三个 Bean 保持既有
 * 注入结构不变（ExecutorAgent/PlannerAgent/JudgeAgent 零改动），实际调用的
 * 客户端在每次请求时按当前配置动态解析——界面改配置即时生效，无需重启。
 */
public class DelegatingChatClient implements ChatClient {

    private final Supplier<ChatClient> delegate;

    public DelegatingChatClient(Supplier<ChatClient> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return delegate.get().prompt();
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        return delegate.get().prompt(content);
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        return delegate.get().prompt(prompt);
    }

    @Override
    public Builder mutate() {
        return delegate.get().mutate();
    }
}
