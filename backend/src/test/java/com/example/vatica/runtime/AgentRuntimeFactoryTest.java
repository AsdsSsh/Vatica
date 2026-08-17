package com.example.vatica.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.config.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 17A：默认 agentscope，legacy 仍可配置回滚。 */
class AgentRuntimeFactoryTest {

    @Test
    void defaultsToAgentScopeRuntime() {
        AgentRuntimeFactory factory = new AgentRuntimeFactory(mock(ModelRegistry.class),
                mock(ToolCallbackProvider.class), new ObjectMapper(),
                new AgentRuntimeProperties(null), mock(ExecutorAgent.class), new AgentRegistry());

        assertThat(factory.runtime().name()).isEqualTo("agentscope");
        assertThat(factory.runtime()).isSameAs(factory.runtime());
    }

    @Test
    void explicitLegacyRuntimeRemainsAvailableForRollback() {
        AgentRuntimeFactory factory = new AgentRuntimeFactory(mock(ModelRegistry.class),
                mock(ToolCallbackProvider.class), new ObjectMapper(),
                new AgentRuntimeProperties("legacy"), mock(ExecutorAgent.class), new AgentRegistry());

        assertThat(factory.runtime().name()).isEqualTo("legacy");
    }
}
