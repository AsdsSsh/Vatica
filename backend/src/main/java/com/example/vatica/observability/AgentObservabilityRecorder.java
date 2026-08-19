package com.example.vatica.observability;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.trace.TraceSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代 21A：统一 Run/Span 写入器。观测写入是 best-effort，数据库故障不会阻断 Agent 业务。
 */
@Service
public class AgentObservabilityRecorder {

    public record SpanStart(RequestIdentity identity, String traceId, String runId,
            String parentSpanId, String taskId, Integer stepId, int attempt,
            String spanType, String name, String runtime, String agentId, String role,
            String modelSlotId, String skillId, String skillVersion, String input) {
    }

    public record SpanHandle(String spanId, String traceId, Long userId, Long orgId) {
    }

    public record SpanFinish(String status, String output, String errorCode, String error,
            Integer inputTokens, Integer outputTokens, Integer totalTokens,
            Integer reasoningTokens, Double contextFillRatio, Double costEstimate,
            Integer judgeScore, String judgeVerdict) {
        public static SpanFinish success(String output) {
            return new SpanFinish(AgentSpanRecord.STATUS_SUCCESS, output, null, null,
                    null, null, null, null, null, null, null, null);
        }

        public static SpanFinish failed(String errorCode, String error) {
            return new SpanFinish(AgentSpanRecord.STATUS_FAILED, null, errorCode, error,
                    null, null, null, null, null, null, null, null);
        }
    }

    private final AgentSpanRecordRepository repository;
    private final ObjectMapper mapper;
    private final AtomicLong dropped = new AtomicLong();

    public AgentObservabilityRecorder(AgentSpanRecordRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public SpanHandle start(SpanStart start) {
        if (start == null || start.identity() == null || start.identity().userId() == null
                || start.identity().orgId() == null) {
            return null;
        }
        String spanId = UUID.randomUUID().toString();
        String traceId = valueOr(start.traceId(), spanId);
        AgentSpanRecord record = new AgentSpanRecord(spanId, traceId, start.parentSpanId(),
                valueOr(start.runId(), traceId), start.identity().userId(), start.identity().orgId(),
                start.taskId(), start.stepId(), start.attempt(), valueOr(start.spanType(), "SPAN"),
                valueOr(start.name(), "Agent span"), start.runtime(), start.agentId(), start.role(),
                start.modelSlotId(), start.skillId(), start.skillVersion(), safeInput(start.input()));
        safeSave(record);
        return new SpanHandle(spanId, traceId, start.identity().userId(), start.identity().orgId());
    }

    public void finish(SpanHandle handle, SpanFinish finish, long startedNanos) {
        if (handle == null || finish == null) {
            return;
        }
        try {
            AgentSpanRecord record = repository.findById(handle.spanId()).orElse(null);
            if (record == null || !handle.userId().equals(record.getUserId())
                    || !handle.orgId().equals(record.getOrgId())) {
                return;
            }
            Instant endedAt = Instant.now();
            long durationMs = startedNanos <= 0
                    ? endedAt.toEpochMilli() - record.getStartedAt().toEpochMilli()
                    : (System.nanoTime() - startedNanos) / 1_000_000;
            String output = finish.output() == null ? null
                    : TraceSanitizer.outputSummary(TraceSanitizer.sanitize(mapper, finish.output()), null);
            String error = finish.error() == null ? null
                    : TraceSanitizer.outputSummary(TraceSanitizer.sanitize(mapper, finish.error()), null);
            record.finish(valueOr(finish.status(), AgentSpanRecord.STATUS_SUCCESS), endedAt, durationMs,
                    output, finish.errorCode(), error, finish.inputTokens(), finish.outputTokens(),
                    finish.totalTokens(), finish.reasoningTokens(), finish.contextFillRatio(),
                    finish.costEstimate(), finish.judgeScore(), finish.judgeVerdict());
            safeSave(record);
        } catch (RuntimeException ignored) {
            dropped.incrementAndGet();
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    private String safeInput(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        return TraceSanitizer.inputSummary(mapper, input);
    }

    private void safeSave(AgentSpanRecord record) {
        try {
            repository.save(record);
        } catch (RuntimeException ignored) {
            dropped.incrementAndGet();
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
