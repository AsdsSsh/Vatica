package com.example.vatica.usage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;

/**
 * 迭代 15 I15-13：挂到 ModelRegistry 构建的所有客户端上——
 * before：平台槽位按调用点预算预留日配额；after：读 ChatResponseMetadata.usage，
 * 落 UsageContext 与 UsageRecorder（异步批量写库），并按实际用量结算预留。
 */
public class UsageAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(UsageAdvisor.class);

    private final UsageRecorder recorder;
    private final UsageQuotaService quota;

    public UsageAdvisor(UsageRecorder recorder, UsageQuotaService quota) {
        this.recorder = recorder;
        this.quota = quota;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        UsageContext.Snapshot ctx = UsageContext.current();
        if (ctx == null || ctx.userId() == null) {
            return request;
        }
        START.set(System.nanoTime());
        int estimate = ctx.budgetTokens() == null ? 8_000 : ctx.budgetTokens();
        if (ctx.platformQuota()) {
            try {
                quota.reserve(ctx.userId(), estimate);
                RESERVED.set(estimate);
            } catch (RuntimeException e) {
                RESERVED.set(0);
                throw e;
            }
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        UsageContext.Snapshot ctx = UsageContext.current();
        Long start = START.get();
        Integer reserved = RESERVED.get();
        START.remove();
        RESERVED.remove();
        if (ctx == null || ctx.userId() == null || response.chatResponse() == null
                || response.chatResponse().getMetadata() == null) {
            return response;
        }
        Usage usage = response.chatResponse().getMetadata().getUsage();
        if (usage == null || usage.getTotalTokens() == null) {
            if (ctx.platformQuota() && reserved != null && reserved > 0) {
                quota.settle(ctx.userId(), 0, reserved);
            }
            return response;
        }
        int input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        int total = usage.getTotalTokens() == null ? input + output : usage.getTotalTokens();
        int reasoning = reasoningTokens(usage);
        long durationMs = start == null ? 0 : (System.nanoTime() - start) / 1_000_000;
        if (ctx.platformQuota() && reserved != null && reserved > 0) {
            quota.settle(ctx.userId(), total, reserved);
        }
        recorder.enqueue(new UsageRecord(UUID.randomUUID().toString(), ctx.requestId(), ctx.userId(),
                ctx.orgId(), ctx.requestType(), ctx.slotId(), ctx.taskId(), ctx.stepId(),
                ctx.reasoningMode(), ctx.agentId(), ctx.role(), null, input, output, total, reasoning,
                usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens(),
                usage.getCacheWriteInputTokens() == null ? 0 : usage.getCacheWriteInputTokens(),
                ctx.contextFillRatio(), durationMs, 0));
        UsageContext.setLastUsageJson(usageJson(input, output, total, reasoning, ctx.contextFillRatio()));
        return response;
    }

    @Override
    public String getName() {
        return "vatica-usage";
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;   // 最外层看真实耗时与最终用量
    }

    private static final ThreadLocal<Long> START = new ThreadLocal<>();
    private static final ThreadLocal<Integer> RESERVED = new ThreadLocal<>();

    private static int reasoningTokens(Usage usage) {
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof Map<?, ?> inner && inner.get("reasoning_tokens") instanceof Number n) {
                    return n.intValue();
                }
            }
        }
        return 0;
    }

    private static String usageJson(int input, int output, int total, int reasoning,
            Integer contextFillRatio) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("inputTokens", input);
            payload.put("outputTokens", output);
            payload.put("totalTokens", total);
            payload.put("reasoningTokens", reasoning);
            payload.put("contextFillRatio", contextFillRatio);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            log.debug("usage JSON 序列化失败", e);
            return "{}";
        }
    }
}
