package com.example.vatica.context;

import java.time.Instant;

import com.example.vatica.controller.SessionSummaryFailureCode;
import com.example.vatica.controller.SessionSummaryStatus;

/**
 * 迭代 29D：上下文健康的脱敏视图。
 *
 * <p>这个 DTO 只描述覆盖水位、计数、状态和恢复时间，不携带摘要正文、消息正文、事实值、
 * 证据引用、用户/组织标识或模型内部推理。</p>
 */
public record ContextHealthView(
        String scopeType,
        String scopeId,
        ContextHealthStatus overallStatus,
        SessionSummaryStatus summaryStatus,
        SessionSummaryFailureCode summaryFailureCode,
        long summaryThroughSeq,
        long summaryRequestedThroughSeq,
        long uncoveredMessageCount,
        int fallbackHeadCount,
        int fallbackTailCount,
        int recentMessageCount,
        int summaryAttemptCount,
        Instant summaryLastAttemptAt,
        Instant summaryLastSuccessAt,
        Instant summaryNextRetryAt,
        int currentFactCount,
        int staleFactCount,
        boolean contextGatePending,
        String reason,
        Instant checkedAt) {
}
