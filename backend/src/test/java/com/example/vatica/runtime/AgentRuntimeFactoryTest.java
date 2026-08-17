package com.example.vatica.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import com.example.vatica.config.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 15 I15-17：默认 legacy；agentscope 未随默认 profile 构建时快速失败并提示。 */
class AgentRuntimeFactoryTest {

    @Test
    void defaultsToLegacyRuntime() {
        AgentRuntimeFactory factory = new AgentRuntimeFactory(mock(ModelRegistry.class),
                mock(ToolCallbackProvider.class), new ObjectMapper(),
                new AgentRuntimeProperties(null));

        assertThat(factory.runtime().name()).isEqualTo("legacy");
    }

    @Test
    void agentscopeWithoutProfileFailsWithActionableMessage() {
        AgentRuntimeFactory factory = new AgentRuntimeFactory(mock(ModelRegistry.class),
                mock(ToolCallbackProvider.class), new ObjectMapper(),
                new AgentRuntimeProperties("agentscope"));

        assertThatThrownBy(factory::runtime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agentscope");
    }
}
