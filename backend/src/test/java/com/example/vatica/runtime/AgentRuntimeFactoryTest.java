package com.example.vatica.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.runtime.AgentRuntime.AdvisoryKind;
import com.example.vatica.runtime.AgentRuntime.AdvisoryRequest;
import com.example.vatica.runtime.AgentRuntime.AdvisoryResult;
import com.example.vatica.runtime.AgentRuntime.StepUsage;
import com.example.vatica.usage.DirectModelUsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 17A：默认 agentscope，legacy 仍可配置回滚。 */
class AgentRuntimeFactoryTest {

    @Test
    void defaultsToAgentScopeRuntime() {
        AgentRuntimeFactory factory = new AgentRuntimeFactory(mock(ModelRegistry.class),
                mock(ToolCallbackProvider.class), new ObjectMapper(),
                new AgentRuntimeProperties(null), mock(ExecutorAgent.class), new AgentRegistry(),
                mock(DirectModelUsageRecorder.class));

        assertThat(factory.runtime().name()).isEqualTo("agentscope");
        assertThat(factory.runtime()).isSameAs(factory.runtime());
    }

    @Test
    void explicitLegacyRuntimeRemainsAvailableForRollback() {
        AgentRuntimeFactory factory = new AgentRuntimeFactory(mock(ModelRegistry.class),
                mock(ToolCallbackProvider.class), new ObjectMapper(),
                new AgentRuntimeProperties("legacy"), mock(ExecutorAgent.class), new AgentRegistry(),
                mock(DirectModelUsageRecorder.class));

        assertThat(factory.runtime().name()).isEqualTo("legacy");
    }

    @Test
    void agentScopeAdvisoryUsesDirectUsageBridge() {
        DirectModelUsageRecorder usage = mock(DirectModelUsageRecorder.class);
        DirectModelUsageRecorder.Reservation reservation =
                new DirectModelUsageRecorder.Reservation(null, 0);
        when(usage.begin()).thenReturn(reservation);
        AgentRuntimeFactory factory = new AgentRuntimeFactory(mock(ModelRegistry.class),
                mock(ToolCallbackProvider.class), new ObjectMapper(),
                new AgentRuntimeProperties(null), mock(ExecutorAgent.class), new AgentRegistry(), usage);
        AgentRuntime runtime = mock(AgentRuntime.class);
        StepUsage stepUsage = new StepUsage(8, 3, 11, 0);
        AdvisoryResult expected = new AdvisoryResult("{}", 17, stepUsage);
        when(runtime.name()).thenReturn(AgentRuntimeProperties.AGENTSCOPE);
        when(runtime.advise(any(AdvisoryRequest.class))).thenReturn(Optional.of(expected));
        ReflectionTestUtils.setField(factory, "runtime", runtime);
        AdvisoryRequest request = new AdvisoryRequest(AdvisoryKind.PLAN, "system", "user",
                new RequestIdentity(1L, 2L, "MEMBER", "alice"), null, "task-1:planner");

        assertThat(factory.advise(request)).contains(expected);
        verify(usage).complete(reservation, stepUsage, 17);
    }
}
