package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.vatica.config.ModelSlot;

/** 迭代 30A：已知模型使用 AgentScope 窗口目录，未知兼容端点保守回退。 */
class AgentScopeContextWindowResolverTest {

    @Test
    void resolvesKnownLargeContextModel() {
        ModelSlot slot = new ModelSlot("qwen", "Qwen", ModelSlot.PROTOCOL_OPENAI,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "key", "qwen-turbo", 0.2, true);

        assertThat(AgentScopeContextWindowResolver.resolve(slot)).isEqualTo(1_000_000);
    }

    @Test
    void fallsBackForUnknownModelAndNullSlot() {
        ModelSlot unknown = new ModelSlot("custom", "Custom", ModelSlot.PROTOCOL_OPENAI,
                "https://example.test/v1", "key", "private-model", 0.2, true);

        assertThat(AgentScopeContextWindowResolver.resolve(unknown))
                .isEqualTo(AgentScopeContextWindowResolver.FALLBACK_CONTEXT_WINDOW);
        assertThat(AgentScopeContextWindowResolver.resolve(null))
                .isEqualTo(AgentScopeContextWindowResolver.FALLBACK_CONTEXT_WINDOW);
    }
}
