package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.context.ContextBudget;

import io.agentscope.core.model.Model;

/** 迭代 30A：模型窗口变化只影响当前调用点的历史预算。 */
class AgentScopeContextBudgetPlannerTest {

    @Test
    void reservesSystemToolsAndCurrentRequestBeforeHistory() {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(2_000);
        AgentScopeContextBudgetPlanner planner = new AgentScopeContextBudgetPlanner(
                new AgentScopeContextProperties(true, 200, 100, 16_000));

        ContextBudget configured = new ContextBudget(1_500, 8_000, 12_000, 16_000, 8_000);
        AgentScopeContextBudgetPlanner.Plan plan = planner.plan(model, ContextBudget.CallSite.CHAT,
                configured, "系统规则".repeat(40), java.util.List.of(), "当前请求".repeat(20));

        assertThat(plan.enabled()).isTrue();
        assertThat(plan.ledger().modelWindowTokens()).isEqualTo(2_000);
        assertThat(plan.ledger().historyBudgetTokens()).isLessThan(configured.chatTokens());
        assertThat(plan.ledger().estimatedInputTokens(plan.historyBudgetTokens())
                + plan.ledger().reservedTokens()).isLessThanOrEqualTo(2_000);
        assertThat(plan.applyTo(configured).chatTokens()).isEqualTo(plan.historyBudgetTokens());
        assertThat(plan.applyTo(configured).plannerTokens()).isEqualTo(configured.plannerTokens());
    }

    @Test
    void disabledPlannerLeavesConfiguredBudgetUntouched() {
        Model model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(500);
        AgentScopeContextBudgetPlanner planner = new AgentScopeContextBudgetPlanner(
                new AgentScopeContextProperties(false, 200, 100, 16_000));
        ContextBudget configured = new ContextBudget(1_500, 8_000, 12_000, 16_000, 8_000);

        AgentScopeContextBudgetPlanner.Plan plan = planner.plan(model, ContextBudget.CallSite.CHAT,
                configured, "很长的系统提示".repeat(100), java.util.List.of(), "请求");

        assertThat(plan.enabled()).isFalse();
        assertThat(plan.historyBudgetTokens()).isEqualTo(configured.chatTokens());
        assertThat(plan.applyTo(configured).chatTokens()).isEqualTo(configured.chatTokens());
    }
}
