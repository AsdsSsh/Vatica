package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextBudgetLedger;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/** 迭代 30B：每轮调用只裁剪发送视图，AgentScope 状态与原始消息列表不变。 */
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
}
