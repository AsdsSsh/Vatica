package com.example.vatica.context;

import org.springframework.stereotype.Component;

import com.example.vatica.config.ContextAllocationProperties;
import com.example.vatica.config.ContextAllocationProperties.ModePolicy;

/**
 * 迭代 31A：将模型能力、请求模式和固定输入区转为可解释的分层预算计划。
 *
 * <p>该类不读取历史，也不直接修改 {@link ContextBudget}。旧调用点仍使用既有 16K 策略；
 * 后续迭代在聊天/任务入口显式接入此计划后，才会按模型窗口扩大原文读取和证据检索范围。</p>
 */
@Component
public final class ContextAllocationPlanner {

    private final ContextAllocationProperties properties;

    public ContextAllocationPlanner(ContextAllocationProperties properties) {
        this.properties = properties == null ? new ContextAllocationProperties() : properties;
    }

    /** 使用模式策略的完整目标块生成计划，适合尚未读取材料前预先取得各层配额。 */
    public ContextAllocationPlan plan(ModelCapabilityProfile capability, ContextMode mode,
            ContextFixedInput fixedInput) {
        ContextMode requested = ContextMode.normalize(mode);
        ModelCapabilityProfile resolved = properties.resolveCapability(capability);
        ContextMode effective = resolveEffectiveMode(requested, resolved);
        return planResolved(resolved, requested, effective, fixedInput, demandFor(properties.policyFor(effective)));
    }

    /**
     * 根据本次实际可用材料分配预算。空缺块不会占用配额，剩余空间会继续让给后续有内容的块，
     * 但每块始终不超过模式策略上限。
     */
    public ContextAllocationPlan plan(ModelCapabilityProfile capability, ContextMode mode,
            ContextFixedInput fixedInput, ContextDynamicDemand demand) {
        ContextMode requested = ContextMode.normalize(mode);
        ModelCapabilityProfile resolved = properties.resolveCapability(capability);
        ContextMode effective = resolveEffectiveMode(requested, resolved);
        return planResolved(resolved, requested, effective, fixedInput,
                demand == null ? ContextDynamicDemand.empty() : demand);
    }

    private ContextAllocationPlan planResolved(ModelCapabilityProfile capability, ContextMode requested,
            ContextMode effective, ContextFixedInput fixedInput, ContextDynamicDemand demand) {
        ModePolicy policy = properties.policyFor(effective);
        ContextFixedInput fixed = fixedInput == null ? ContextFixedInput.empty() : fixedInput;
        int outputReserve = Math.min(policy.outputReserveTokens(), capability.maxOutputTokens());
        int inputCapacity = subtract(capability.contextWindowTokens(), outputReserve, policy.safetyMarginTokens());
        int availableDynamic = subtract(inputCapacity, fixed.totalTokens());
        int dynamicCap = Math.min(Math.min(availableDynamic, policy.maximumDynamicTokens()),
                policy.windowScopedDynamicTokens(capability.contextWindowTokens()));
        ContextDynamicDemand cappedDemand = capDemand(demand, policy);
        ContextDynamicBudget dynamicBudget = allocate(cappedDemand, dynamicCap);
        return new ContextAllocationPlan(requested, effective, capability, outputReserve,
                policy.safetyMarginTokens(), fixed, demand, dynamicBudget, inputCapacity,
                availableDynamic, dynamicCap);
    }

    private ContextMode resolveEffectiveMode(ContextMode requested, ModelCapabilityProfile capability) {
        ContextMode candidate = requested;
        while (candidate != ContextMode.NORMAL
                && capability.contextWindowTokens() < properties.policyFor(candidate).minimumModelWindowTokens()) {
            candidate = candidate.fallback();
        }
        return candidate;
    }

    private static ContextDynamicDemand demandFor(ModePolicy policy) {
        return new ContextDynamicDemand(policy.verifiedFactsTokens(), policy.summaryTokens(),
                policy.recentHistoryTokens(), policy.historicalEvidenceTokens(), policy.ragEvidenceTokens());
    }

    private static ContextDynamicDemand capDemand(ContextDynamicDemand demand, ModePolicy policy) {
        return new ContextDynamicDemand(
                Math.min(demand.verifiedFactsTokens(), policy.verifiedFactsTokens()),
                Math.min(demand.summaryTokens(), policy.summaryTokens()),
                Math.min(demand.recentHistoryTokens(), policy.recentHistoryTokens()),
                Math.min(demand.historicalEvidenceTokens(), policy.historicalEvidenceTokens()),
                Math.min(demand.ragEvidenceTokens(), policy.ragEvidenceTokens()));
    }

    /** 动态材料的优先级：可信事实 -> 摘要 -> 近期原文 -> 历史证据 -> RAG 证据。 */
    private static ContextDynamicBudget allocate(ContextDynamicDemand demand, int capacity) {
        int remaining = Math.max(0, capacity);
        int facts = take(demand.verifiedFactsTokens(), remaining);
        remaining -= facts;
        int summary = take(demand.summaryTokens(), remaining);
        remaining -= summary;
        int recent = take(demand.recentHistoryTokens(), remaining);
        remaining -= recent;
        int history = take(demand.historicalEvidenceTokens(), remaining);
        remaining -= history;
        int rag = take(demand.ragEvidenceTokens(), remaining);
        return new ContextDynamicBudget(facts, summary, recent, history, rag);
    }

    private static int take(int requested, int remaining) {
        return Math.min(Math.max(0, requested), Math.max(0, remaining));
    }

    private static int subtract(int value, int... deductions) {
        long remaining = Math.max(0, value);
        for (int deduction : deductions) {
            remaining -= Math.max(0, deduction);
        }
        return remaining <= 0 ? 0 : remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }
}
