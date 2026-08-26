package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextBudgetLedger;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/** 迭代 30B/30D：每轮调用只裁剪发送视图，执行期工具门禁与原始状态保持一致。 */
class AgentScopeContextBudgetMiddlewareTest {

    @Test
    void trimsOldCompleteTurnsAndPreservesSystemAndCurrentTurn() {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(30);
        AgentScopeContextBudgetMiddleware middleware = new AgentScopeContextBudgetMiddleware(model,
                "系统规则", ContextBudget.CallSite.CHAT, 1_000,
                new AgentScopeContextProperties(true, 8, 4, 16_000));
        List<Msg> original = List.of(
                new SystemMessage("系统规则"),
                new UserMessage("旧请求"),
                new AssistantMessage("旧回答"),
                new UserMessage("当前请求"),
                new AssistantMessage("当前回合工具结果"));
        ModelCallInput input = new ModelCallInput(original, List.of(),
                GenerateOptions.builder().build(), model);
        AtomicReference<ModelCallInput> seen = new AtomicReference<>();

        middleware.onModelCall(null, null, input, next -> {
            seen.set(next);
            return Flux.empty();
        }).blockLast();

        assertThat(original).hasSize(5);
        assertThat(seen.get().messages()).extracting(Msg::getTextContent)
                .containsExactly("系统规则", "当前请求", "当前回合工具结果");
        ContextBudgetLedger ledger = middleware.lastLedger();
        assertThat(ledger).isNotNull();
        assertThat(ledger.callSite()).isEqualTo(ContextBudget.CallSite.CHAT);
        assertThat(ledger.historyBudgetTokens()).isLessThan(ledger.requestedHistoryTokens());
    }

    @Test
    void removesAWholeHistoricalTurnInsteadOfTruncatingOneMessage() {
        List<Msg> messages = List.of(
                new SystemMessage("system"),
                new UserMessage("old user"),
                new AssistantMessage("old assistant"),
                new UserMessage("new user"));

        List<Msg> trimmed = AgentScopeContextBudgetMiddleware.trimHistory(messages, 3, 0);

        assertThat(trimmed).extracting(Msg::getTextContent)
                .containsExactly("system", "new user");
    }

    @Test
    void disabledMiddlewarePassesTheOriginalInputThrough() {
        ModelCallInput input = new ModelCallInput(List.of(new UserMessage("request")), List.of(),
                GenerateOptions.builder().build(), null);
        AgentScopeContextBudgetMiddleware middleware = new AgentScopeContextBudgetMiddleware(null,
                "system", ContextBudget.CallSite.CHAT, 100,
                new AgentScopeContextProperties(false, 8, 4, 16_000));
        AtomicReference<ModelCallInput> seen = new AtomicReference<>();

        middleware.onModelCall(null, null, input, next -> {
            seen.set(next);
            return Flux.empty();
        }).blockLast();

        assertThat(seen.get()).isSameAs(input);
        assertThat(middleware.lastLedger()).isNull();
    }

    @Test
    void keepsRelevantToolSchemasWhenAllSchemasDoNotFit() {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(800);
        ToolSchema calculator = schema("calculator", "计算金额和预算".repeat(80));
        ToolSchema mail = schema("mail_send", "发送邮件".repeat(80));
        AgentScopeContextBudgetMiddleware middleware = new AgentScopeContextBudgetMiddleware(model,
                "系统规则", ContextBudget.CallSite.CHAT, 1_000,
                new AgentScopeContextProperties(true, 32, 8, 16_000));
        ModelCallInput input = new ModelCallInput(List.of(new UserMessage("请计算本月预算")),
                List.of(calculator, mail), GenerateOptions.builder().build(), model);
        AtomicReference<ModelCallInput> seen = new AtomicReference<>();

        middleware.onModelCall(null, null, input, next -> {
            seen.set(next);
            return Flux.empty();
        }).blockLast();

        assertThat(seen.get().tools()).extracting(ToolSchema::getName)
                .containsExactly("calculator");
        assertThat(middleware.lastLedger().toolSchemaTokens()).isLessThan(
                estimate(calculator) + estimate(mail));
    }

    @Test
    void usesChineseDescriptionOverlapForCustomToolNames() {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(700);
        ToolSchema approval = schema("custom_action", "处理审批申请和审批状态".repeat(50));
        ToolSchema unrelated = schema("other_action", "归档无关日志".repeat(50));
        AgentScopeContextBudgetMiddleware middleware = new AgentScopeContextBudgetMiddleware(model,
                "系统规则", ContextBudget.CallSite.CHAT, 1_000,
                new AgentScopeContextProperties(true, 32, 8, 16_000));
        ModelCallInput input = new ModelCallInput(List.of(new UserMessage("请处理审批申请")),
                List.of(approval, unrelated), GenerateOptions.builder().build(), model);
        AtomicReference<ModelCallInput> seen = new AtomicReference<>();

        middleware.onModelCall(null, null, input, next -> {
            seen.set(next);
            return Flux.empty();
        }).blockLast();

        assertThat(seen.get().tools()).extracting(ToolSchema::getName)
                .containsExactly("custom_action");
    }

    @Test
    void executionScopeRejectsTrimmedToolAndRestoresBaselineGroups() {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(800);
        AtomicInteger calculatorCalls = new AtomicInteger();
        AtomicInteger mailCalls = new AtomicInteger();
        AgentTool calculator = tool("calculator", calculatorCalls);
        AgentTool mail = tool("mail_send", mailCalls);
        Toolkit toolkit = new Toolkit();
        AgentScopeToolGroupAdapter.registerAll(toolkit, new AgentTool[] { calculator, mail });
        List<String> baselineGroups = toolkit.getActiveGroups();
        AgentScopeContextBudgetMiddleware middleware = new AgentScopeContextBudgetMiddleware(model,
                "系统规则", ContextBudget.CallSite.CHAT, 1_000,
                new AgentScopeContextProperties(true, 32, 8, 16_000));
        ModelCallInput input = new ModelCallInput(List.of(new UserMessage("请计算本月预算")),
                List.of(schema("calculator", "计算金额和预算".repeat(80)),
                        schema("mail_send", "发送邮件".repeat(80))),
                GenerateOptions.builder().build(), model);
        middleware.onModelCall(null, null, input, ignored -> Flux.empty()).blockLast();

        Agent agent = mock(Agent.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        middleware.onActing(agent, null, new ActingInput(List.of()), ignored -> {
            ToolResultBlock denied = toolkit.callTool(call("mail_send")).block();
            ToolResultBlock allowed = toolkit.callTool(call("calculator")).block();
            assertThat(denied).isNotNull();
            assertThat(denied.getOutput()).anySatisfy(output ->
                    assertThat(output.toString()).contains("Unauthorized tool call"));
            assertThat(allowed).isNotNull();
            assertThat(allowed.getOutput()).anySatisfy(output ->
                    assertThat(output.toString()).contains("calculator ok"));
            return Flux.empty();
        }).blockLast();

        assertThat(mailCalls).hasValue(0);
        assertThat(calculatorCalls).hasValue(1);
        assertThat(toolkit.getActiveGroups()).containsExactlyElementsOf(baselineGroups);
        assertThat(toolkit.getToolSchemas()).extracting(ToolSchema::getName)
                .containsExactly("calculator", "mail_send");
    }

    private static AgentTool tool(String name, AtomicInteger calls) {
        return new AgentTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParameters() { return Map.of("type", "object"); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                calls.incrementAndGet();
                return Mono.just(ToolResultBlock.text(name + " ok"));
            }
        };
    }

    private static ToolCallParam call(String name) {
        ToolUseBlock use = ToolUseBlock.builder().id("test-" + name).name(name)
                .input(Map.of()).content("{}").build();
        return ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();
    }

    private static ToolSchema schema(String name, String description) {
        ToolSchema schema = mock(ToolSchema.class);
        when(schema.getName()).thenReturn(name);
        when(schema.getDescription()).thenReturn(description);
        when(schema.getParameters()).thenReturn(Map.of("type", "object"));
        when(schema.getOutputSchema()).thenReturn(Map.of());
        return schema;
    }

    private static int estimate(ToolSchema schema) {
        return com.example.vatica.context.TokenEstimator.estimate(
                List.of(schema.getName(), schema.getDescription(),
                        String.valueOf(schema.getParameters()), String.valueOf(schema.getOutputSchema())));
    }
}
