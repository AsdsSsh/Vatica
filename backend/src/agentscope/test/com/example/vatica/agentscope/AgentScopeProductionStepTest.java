package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.example.vatica.runtime.AgentRuntime.AdvisoryKind;
import com.example.vatica.runtime.AgentRuntime.AdvisoryRequest;
import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;
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
    void plannerAndJudgeAdvisoriesRunWithoutToolsAndReturnRawUsage() {
        AtomicReference<List<ToolSchema>> seenTools = new AtomicReference<>();
        AtomicReference<String> seenConversation = new AtomicReference<>();
        Model deterministicModel = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                    List<ToolSchema> tools, GenerateOptions options) {
                seenTools.set(tools);
                seenConversation.set(messages.stream().map(io.agentscope.core.message.Msg::getTextContent)
                        .collect(java.util.stream.Collectors.joining("\n")));
                return Flux.just(ChatResponse.builder().id("advisory-response")
                        .content(List.of(TextBlock.builder().text("{\"score\":40,\"verdict\":\"PASS\"}").build()))
                        .usage(new ChatUsage(9, 4, 0, 0.01)).metadata(Map.of()).finishReason("stop").build());
            }

            @Override
            public String getModelName() {
                return "deterministic-advisory-model";
            }
        };
        RequestIdentity identity = new RequestIdentity(7L, 9L, "MEMBER", "alice");
        ModelSlot slot = new ModelSlot("judge-test", "Judge Test", ModelSlot.PROTOCOL_OPENAI,
                "http://localhost", "", "test", 0.0, true);
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.activeSlotFor(ModelSlot.CAP_JUDGE)).thenReturn(slot);
        AgentScopeRuntime runtime = new AgentScopeRuntime(registry, () -> new ToolCallback[0],
                new ObjectMapper(), new AgentRegistry(), selected -> deterministicModel);

        var result = runtime.advise(new AdvisoryRequest(AdvisoryKind.JUDGE,
                "只输出评分 JSON，不得调用工具。", "评测任务结果", identity, null, "task-1:judge"))
                .orElseThrow();

        assertThat(result.content()).contains("\"score\":40", "\"verdict\":\"PASS\"");
        assertThat(result.usage().totalTokens()).isEqualTo(13);
        assertThat(seenTools.get()).isEmpty();
        assertThat(seenConversation.get()).contains("只输出评分 JSON", "评测任务结果");
        verify(registry).activeSlotFor(ModelSlot.CAP_JUDGE);
    }

    @Test
    void executesProductionStepWithRoleScopedToolkitAndContext() {
        AtomicReference<List<ToolSchema>> seenTools = new AtomicReference<>();
        AtomicReference<String> seenConversation = new AtomicReference<>();
        Model deterministicModel = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                    List<ToolSchema> tools, GenerateOptions options) {
                seenTools.set(tools);
                seenConversation.set(messages.stream().map(io.agentscope.core.message.Msg::getTextContent)
                        .collect(java.util.stream.Collectors.joining("\n")));
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
        ToolCallback excel = callback("create_excel_stats");
        ExecutionProfile skill = new ExecutionProfile("document-delivery", "1.0.0", "文档交付", "document",
                List.of("create_word_report"), List.of("workspace:write"), "只生成已确认的 Word 内容。");
        AgentRuntime.StepRequest request = new AgentRuntime.StepRequest(
                "生成周报", step, List.of("步骤 1：数据已核验"), "补齐来源",
                new RequestIdentity(7L, 9L, "MEMBER", "alice"), new ToolCallback[] { word, excel },
                mock(ChatClient.class), new ModelSlot("test", "Test", ModelSlot.PROTOCOL_OPENAI,
                        "http://localhost", "", "test", 0.0, true),
                roles.resolve("document"), "task-1:step:2", skill);

        AgentRuntime.StepResult result = runtime.executeStep(request);

        assertThat(result.answer()).isEqualTo("AgentScope 步骤完成");
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().totalTokens()).isEqualTo(17);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(1);
        assertThat(seenTools.get()).extracting(ToolSchema::getName)
                .containsExactly("create_word_report");
        assertThat(seenConversation.get()).contains("document-delivery@1.0.0", "只生成已确认的 Word 内容",
                "资源上限：推理轮次 3，工具调用 2 次，单次工具输出 6000 字符",
                "生成周报", "数据已核验", "补齐来源", "生成 Word 报告");
    }

    @Test
    void rejectsSkillWhenDeclaredToolIsMissingFromVaticaAuthorizedCallbacks() {
        AgentRegistry roles = new AgentRegistry();
        AgentScopeRuntime runtime = new AgentScopeRuntime(mock(ModelRegistry.class), () -> new ToolCallback[0],
                new ObjectMapper(), roles, slot -> mock(Model.class));
        TaskStep step = new TaskStep(1, "生成 Word 报告", false);
        step.setAgent("document");
        ExecutionProfile skill = new ExecutionProfile("document-delivery", "1.0.0", "文档交付", "document",
                List.of("create_word_report", "create_excel_stats"), List.of("workspace:write"), "生成文档");
        AgentRuntime.StepRequest request = new AgentRuntime.StepRequest(
                "生成周报", step, List.of(), null, new RequestIdentity(7L, 9L, "MEMBER", "alice"),
                new ToolCallback[] { callback("create_word_report") }, mock(ChatClient.class),
                new ModelSlot("test", "Test", ModelSlot.PROTOCOL_OPENAI, "http://localhost", "", "test", 0.0, true),
                roles.resolve("document"), "task-2:step:1", skill);

        assertThatThrownBy(() -> runtime.executeStep(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create_excel_stats");
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{}").build());
        return callback;
    }
}
