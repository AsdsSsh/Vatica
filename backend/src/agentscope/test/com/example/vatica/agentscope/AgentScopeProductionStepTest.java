package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.runtime.AgentRuntime;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/** 迭代 17A：不访问外网，真实穿过 AgentScope ReActAgent 的生产步骤契约。 */
class AgentScopeProductionStepTest {

    @Test
    void executesProductionStepWithRoleScopedToolkitAndContext() {
        AtomicReference<List<ToolSchema>> seenTools = new AtomicReference<>();
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        Model deterministicModel = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                    List<ToolSchema> tools, GenerateOptions options) {
                seenTools.set(tools);
                seenPrompt.set(messages.get(messages.size() - 1).getTextContent());
                ContentBlock text = TextBlock.builder().text("AgentScope 步骤完成").build();
                return Flux.just(ChatResponse.builder().id("test-response")
                        .content(List.of(text)).usage(new ChatUsage(12, 5, 1, 0.01))
                        .metadata(Map.of()).finishReason("stop").build());
            }

            @Override
            public String getModelName() {
                return "deterministic-test-model";
            }
        };
        AgentRegistry roles = new AgentRegistry();
        AgentScopeRuntime runtime = new AgentScopeRuntime(mock(ModelRegistry.class), () -> new ToolCallback[0],
                new ObjectMapper(), roles, slot -> deterministicModel);
        TaskStep step = new TaskStep(2, "生成 Word 报告", false);
        step.setAgent("document");
        ToolCallback word = callback("create_word_report");
        AgentRuntime.StepRequest request = new AgentRuntime.StepRequest(
                "生成周报", step, List.of("步骤 1：数据已核验"), "补齐来源",
                new RequestIdentity(7L, 9L, "MEMBER", "alice"), new ToolCallback[] { word },
                mock(ChatClient.class), new ModelSlot("test", "Test", ModelSlot.PROTOCOL_OPENAI,
                        "http://localhost", "", "test", 0.0, true),
                roles.resolve("document"), "task-1:step:2");

        AgentRuntime.StepResult result = runtime.executeStep(request);

        assertThat(result.answer()).isEqualTo("AgentScope 步骤完成");
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().totalTokens()).isEqualTo(17);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(1);
        assertThat(seenTools.get()).extracting(ToolSchema::getName)
                .containsExactly("create_word_report");
        assertThat(seenPrompt.get()).contains("生成周报", "数据已核验", "补齐来源", "生成 Word 报告");
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{}").build());
        return callback;
    }
}
