package com.example.vatica.usage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.example.vatica.runtime.AgentRuntime.StepUsage;

/**
 * 迭代 17A：为不经过 Spring AI Advisor 的直连运行时复用平台配额与 usage 表。
 * LegacyRuntime 仍由 UsageAdvisor 记账，调用方不得同时使用两条链路。
 */
@Component
public class DirectModelUsageRecorder {

    private final UsageRecorder recorder;
    private final UsageQuotaService quota;

    public DirectModelUsageRecorder(UsageRecorder recorder, UsageQuotaService quota) {
        this.recorder = recorder;
        this.quota = quota;
    }

    public Reservation begin() {
        UsageContext.Snapshot context = UsageContext.current();
        if (context == null || context.userId() == null) {
            return new Reservation(context, 0);
        }
        int estimate = context.budgetTokens() == null ? 8_000 : context.budgetTokens();
        if (context.platformQuota()) {
            quota.reserve(context.userId(), estimate);
            return new Reservation(context, estimate);
        }
        return new Reservation(context, 0);
    }

    public void complete(Reservation reservation, StepUsage usage, long durationMs) {
        if (reservation == null || reservation.context() == null || reservation.context().userId() == null) {
            return;
        }
        UsageContext.Snapshot context = reservation.context();
        int actual = usage == null ? 0 : usage.totalTokens();
        settle(reservation, actual);
        if (usage == null) {
            return;
        }
        recorder.enqueue(new UsageRecord(UUID.randomUUID().toString(), context.requestId(), context.userId(),
                context.orgId(), context.requestType(), context.slotId(), context.taskId(), context.stepId(),
                context.reasoningMode(), context.agentId(), context.role(), null,
                usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), 0,
                usage.cacheReadTokens(), 0, context.contextFillRatio(), durationMs, 0));
        UsageContext.setLastUsageJson(usageJson(usage, context.contextFillRatio()));
    }

    /** 迭代 17C：明确绑定失效时记录零 token 的降级观测，不伪装成模型调用。 */
    public void recordFallback(String reason, String slotId) {
        UsageContext.Snapshot context = UsageContext.current();
        if (context == null || context.userId() == null) {
            return;
        }
        recorder.enqueue(new UsageRecord(UUID.randomUUID().toString(), context.requestId(), context.userId(),
                context.orgId(), "MODEL_FALLBACK", slotId, context.taskId(), context.stepId(),
                context.reasoningMode(), context.agentId(), context.role(), reason,
                0, 0, 0, 0, 0, 0, context.contextFillRatio(), 0, 0));
    }

    /** 模型调用失败时释放预留，不让失败请求永久占用进程内额度。 */
    public void abort(Reservation reservation) {
        settle(reservation, 0);
    }

    private void settle(Reservation reservation, int actualTokens) {
        if (reservation != null && reservation.context() != null
                && reservation.context().platformQuota() && reservation.reservedTokens() > 0) {
            quota.settle(reservation.context().userId(), actualTokens, reservation.reservedTokens());
        }
    }

    private static String usageJson(StepUsage usage, Integer contextFillRatio) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputTokens", usage.inputTokens());
            payload.put("outputTokens", usage.outputTokens());
            payload.put("totalTokens", usage.totalTokens());
            payload.put("reasoningTokens", 0);
            payload.put("contextFillRatio", contextFillRatio);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    public record Reservation(UsageContext.Snapshot context, int reservedTokens) {
    }
}
