package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextBudgetLedger;
import com.example.vatica.context.TokenEstimator;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.AgentTool;

/**
 * 迭代 30A：在进入 AgentScope 前计算一次可解释的上下文预算。
 * AgentScope 负责模型窗口元数据；Vatica 只负责把业务上下文拆成可观测预算段。
 */
@Component
public final class AgentScopeContextBudgetPlanner {

    private final AgentScopeContextProperties properties;

    public AgentScopeContextBudgetPlanner(AgentScopeContextProperties properties) {
        this.properties = properties;
    }

    public Plan plan(Model model, ContextBudget.CallSite callSite, ContextBudget configured,
            String systemPrompt, Collection<AgentTool> tools, String currentRequest) {
        ContextBudget budget = configured == null ? new ContextBudget(0, 0, 0, 0, 0) : configured;
        ContextBudget.CallSite site = callSite == null ? ContextBudget.CallSite.CHAT : callSite;
        int requestedHistory = budget.tokensFor(site);
        int modelWindow = model == null || model.getContextWindowSize() <= 0
                ? properties.fallbackModelWindowTokens() : model.getContextWindowSize();
        int systemTokens = TokenEstimator.estimate(systemPrompt);
        int toolTokens = estimateToolSchemas(tools);
        int currentTokens = TokenEstimator.estimate(currentRequest);
        int available = remainingCapacity(modelWindow, properties.outputReserveTokens(),
                properties.safetyMarginTokens(), systemTokens, toolTokens, currentTokens);
        int historyBudget = Math.min(requestedHistory, available);
        ContextBudgetLedger ledger = new ContextBudgetLedger(site, modelWindow, requestedHistory,
                properties.outputReserveTokens(), properties.safetyMarginTokens(), systemTokens,
                toolTokens, currentTokens, historyBudget);
        return new Plan(ledger, properties.enabledValue());
    }

    public static int estimateToolSchemas(Collection<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        List<String> descriptions = new ArrayList<>();
        for (AgentTool tool : tools) {
            if (tool == null) {
                continue;
            }
            descriptions.add(tool.getName());
            descriptions.add(tool.getDescription());
            descriptions.add(String.valueOf(tool.getParameters()));
            descriptions.add(String.valueOf(tool.getOutputSchema()));
        }
        int total = 0;
        for (String description : descriptions) {
            total = saturatedAdd(total, TokenEstimator.estimate(description));
        }
        return total;
    }

    private static int remainingCapacity(int window, int... consumed) {
        long remaining = Math.max(0, window);
        for (int value : consumed) {
            remaining -= Math.max(0, value);
        }
        if (remaining <= 0) {
            return 0;
        }
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    public record Plan(ContextBudgetLedger ledger, boolean enabled) {
        public Plan {
            if (ledger == null) {
                throw new IllegalArgumentException("上下文预算账本不能为空");
            }
        }

        public int historyBudgetTokens() {
            return enabled ? ledger.historyBudgetTokens() : ledger.requestedHistoryTokens();
        }

        public ContextBudget applyTo(ContextBudget configured) {
            ContextBudget source = configured == null ? new ContextBudget(0, 0, 0, 0, 0) : configured;
            return source.with(ledger.callSite(), historyBudgetTokens());
        }
    }
}
