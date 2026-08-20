package com.example.vatica.trace;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.vatica.event.SseEventSink;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.observability.AgentObservabilityRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 显式 ReAct trace 装饰器（迭代 15 I15-1）：
 * 包装每个工具调用，记录 Action（工具名 + 脱敏输入摘要）/ Observation（输出头尾摘要 + 长度）/
 * 耗时与失败原因。聊天链路经 {@code tool_activity} SSE 推送（persist=false）；
 * 任务链路把脱敏摘要写入 {@code agent_trace}（persist=true），失败只记录不阻断主流程。
 *
 * <p>装饰顺序（内→外）：ToolCallLimitProvider → PermissionBoundToolCallbacks →（迭代 15.3
 * RetryableToolCallbacks）→ 本类。trace 在最外层看到真实耗时与最终结果。
 */
public final class TracedToolCallbacks {

    private static final Logger log = LoggerFactory.getLogger(TracedToolCallbacks.class);

    private final ObjectMapper mapper;
    private final SseEventSink eventSink;
    private final AgentTraceRecordRepository traceRepository;
    private final AgentObservabilityRecorder observability;

    public TracedToolCallbacks(ObjectMapper mapper, SseEmitter emitter,
            AgentTraceRecordRepository traceRepository) {
        this(mapper, emitter, traceRepository, null);
    }

    public TracedToolCallbacks(ObjectMapper mapper, SseEmitter emitter,
            AgentTraceRecordRepository traceRepository, AgentObservabilityRecorder observability) {
        this.mapper = mapper;
        this.eventSink = emitter == null ? null : emitterSink(mapper, emitter);
        this.traceRepository = traceRepository;
        this.observability = observability;
    }

    /** 聊天生产链路：事件通过统一网关发布，不直接写 SseEmitter。 */
    public TracedToolCallbacks(ObjectMapper mapper, SseEventSink eventSink,
            AgentTraceRecordRepository traceRepository) {
        this(mapper, eventSink, traceRepository, null);
    }

    public TracedToolCallbacks(ObjectMapper mapper, SseEventSink eventSink,
            AgentTraceRecordRepository traceRepository, AgentObservabilityRecorder observability) {
        this.mapper = mapper;
        this.eventSink = eventSink;
        this.traceRepository = traceRepository;
        this.observability = observability;
    }

    /** 聊天：只发 SSE，不落库。 */
    public TracedToolCallbacks(ObjectMapper mapper, SseEmitter emitter) {
        this(mapper, emitter, null);
    }

    /** 聊天生产链路：事件通过统一网关发布，不落库。 */
    public TracedToolCallbacks(ObjectMapper mapper, SseEventSink eventSink) {
        this(mapper, eventSink, null);
    }

    /** 任务：落 agent_trace；无 SSE 订阅者时零开销。 */
    public TracedToolCallbacks(ObjectMapper mapper, AgentTraceRecordRepository traceRepository) {
        this(mapper, (SseEmitter) null, traceRepository);
    }

    /** 任务链路：同时写旧版工具 trace 和统一 Span。 */
    public TracedToolCallbacks(ObjectMapper mapper, AgentTraceRecordRepository traceRepository,
            AgentObservabilityRecorder observability) {
        this(mapper, (SseEmitter) null, traceRepository, observability);
    }

    public ToolCallback[] wrap(ToolCallback[] callbacks, TraceContext.Snapshot trace) {
        return wrap(callbacks, trace, com.example.vatica.tool.ToolResultPolicy.MAX_OUTPUT_CHARS);
    }

    /** 迭代 20D：在保留原始 outputLength 的同时，按 Skill 额度收窄模型可见输出。 */
    public ToolCallback[] wrap(ToolCallback[] callbacks, TraceContext.Snapshot trace, int maxOutputChars) {
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = wrapOne(callbacks[i], trace, maxOutputChars);
        }
        return wrapped;
    }

    private ToolCallback wrapOne(ToolCallback delegate, TraceContext.Snapshot trace, int maxOutputChars) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                String tool = delegate.getToolDefinition().name();
                String inputSummary = TraceSanitizer.inputSummary(mapper, toolInput);
                long start = System.nanoTime();
                AgentObservabilityRecorder.SpanHandle span = startSpan(tool, trace, inputSummary);
                send(startPayload(tool, trace, inputSummary));
                try {
                    String out = delegate.call(toolInput);
                    // 迭代 15 I15-10：超限输出先按预算截断再交给模型；trace 记录原始长度
                    String modelVisible = com.example.vatica.tool.ToolResultPolicy.limit(out, maxOutputChars);
                    long durationMs = (System.nanoTime() - start) / 1_000_000;
                    String outputSummary = TraceSanitizer.outputSummary(
                            TraceSanitizer.sanitize(mapper, modelVisible), null);
                    int outputLength = out.length();
                    send(endPayload(tool, trace, durationMs, inputSummary, outputSummary, outputLength, null));
                    persist(trace, tool, inputSummary, outputSummary, outputLength, durationMs,
                            AgentTraceRecord.STATUS_SUCCESS, null);
                    finishSpan(span, AgentObservabilityRecorder.SpanFinish.success(modelVisible), start);
                    return modelVisible;
                } catch (RuntimeException e) {
                    long durationMs = (System.nanoTime() - start) / 1_000_000;
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    if (message.length() > 500) {
                        message = message.substring(0, 500) + "…";
                    }
                    send(endPayload(tool, trace, durationMs, inputSummary, "", 0, message));
                    persist(trace, tool, inputSummary, "", 0, durationMs,
                            AgentTraceRecord.STATUS_FAILED, message);
                    finishSpan(span, AgentObservabilityRecorder.SpanFinish.failed("TOOL_ERROR", message), start);
                    throw e;
                }
            }
        };
    }

    /** 迭代 22B：AgentScope 原生工具 Trace，保留既有 SSE/持久化 Span 契约。 */
    public AgentTool[] wrap(AgentTool[] tools, TraceContext.Snapshot trace, int maxOutputChars) {
        AgentTool[] wrapped = new AgentTool[tools == null ? 0 : tools.length];
        for (int i = 0; i < wrapped.length; i++) {
            wrapped[i] = wrapOne(tools[i], trace, maxOutputChars);
        }
        return wrapped;
    }

    private AgentTool wrapOne(AgentTool delegate, TraceContext.Snapshot trace, int maxOutputChars) {
        return new AgentTool() {
            @Override public String getName() { return delegate.getName(); }
            @Override public String getDescription() { return delegate.getDescription(); }
            @Override public java.util.Map<String, Object> getParameters() { return delegate.getParameters(); }
            @Override public Boolean getStrict() { return delegate.getStrict(); }
            @Override public java.util.Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
            @Override public boolean isReadOnly() { return delegate.isReadOnly(); }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                String tool = delegate.getName();
                String input = writeInput(param.getInput());
                String inputSummary = TraceSanitizer.inputSummary(mapper, input);
                long started = System.nanoTime();
                AgentObservabilityRecorder.SpanHandle span = startSpan(tool, trace, inputSummary);
                send(startPayload(tool, trace, inputSummary));
                return delegate.callAsync(param).map(result -> {
                    String raw = result == null || result.getOutput().isEmpty() ? "" : result.getOutput().getFirst().toString();
                    String visible = com.example.vatica.tool.ToolResultPolicy.limit(raw, maxOutputChars);
                    long duration = (System.nanoTime() - started) / 1_000_000;
                    String outputSummary = TraceSanitizer.outputSummary(TraceSanitizer.sanitize(mapper, visible), null);
                    send(endPayload(tool, trace, duration, inputSummary, outputSummary, raw.length(), null));
                    persist(trace, tool, inputSummary, outputSummary, raw.length(), duration,
                            AgentTraceRecord.STATUS_SUCCESS, null);
                    finishSpan(span, AgentObservabilityRecorder.SpanFinish.success(visible), started);
                    return ToolResultBlock.text(visible);
                }).doOnError(error -> {
                    long duration = (System.nanoTime() - started) / 1_000_000;
                    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    send(endPayload(tool, trace, duration, inputSummary, "", 0, message));
                    persist(trace, tool, inputSummary, "", 0, duration, AgentTraceRecord.STATUS_FAILED, message);
                    finishSpan(span, AgentObservabilityRecorder.SpanFinish.failed("TOOL_ERROR", message), started);
                });
            }
        };
    }

    private String writeInput(Map<String, Object> input) {
        try {
            return mapper.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception e) {
            return "{}";
        }
    }

    private AgentObservabilityRecorder.SpanHandle startSpan(String tool, TraceContext.Snapshot trace,
            String inputSummary) {
        if (observability == null || !trace.persist() || trace.userId() == null || trace.orgId() == null) {
            return null;
        }
        return observability.start(new AgentObservabilityRecorder.SpanStart(
                new RequestIdentity(trace.userId(), trace.orgId(), trace.role(), null), trace.traceId(),
                trace.runId() == null ? trace.taskId() : trace.runId(), trace.spanId(), trace.taskId(),
                trace.stepId(), trace.attempt(), "TOOL_CALL", tool, trace.runtime(), trace.agentId(),
                trace.role(), trace.modelSlotId(), trace.skillId(), trace.skillVersion(), inputSummary));
    }

    private void finishSpan(AgentObservabilityRecorder.SpanHandle span,
            AgentObservabilityRecorder.SpanFinish finish, long startedNanos) {
        if (observability != null) {
            observability.finish(span, finish, startedNanos);
        }
    }

    private void persist(TraceContext.Snapshot trace, String tool, String inputSummary,
            String outputSummary, int outputLength, long durationMs, String status, String error) {
        if (!trace.persist() || trace.taskId() == null || traceRepository == null) {
            return;
        }
        try {
            traceRepository.save(new AgentTraceRecord(UUID.randomUUID().toString(),
                    trace.userId(), trace.orgId(), trace.taskId(), trace.stepId(), trace.traceId(),
                    trace.agentId(), trace.role(), tool, inputSummary, outputSummary,
                    trace.skillId(), trace.skillVersion(), trace.skillPermissions(), outputLength, durationMs, status,
                    error));
        } catch (Exception e) {
            // 观测数据可丢，业务不因 trace 写失败而失败（迭代 15 既定：观测不阻塞业务）
            log.warn("agent_trace 写入失败：task={} tool={}", trace.taskId(), tool, e);
        }
    }

    private void send(Map<String, Object> payload) {
        if (eventSink == null) {
            return;
        }
        eventSink.emit("tool_activity", payload);
    }

    private static SseEventSink emitterSink(ObjectMapper mapper, SseEmitter emitter) {
        return (type, payload) -> {
            try {
                emitter.send(SseEmitter.event().name(type).data(mapper.writeValueAsString(payload)));
                return true;
            } catch (IOException | IllegalStateException e) {
                // 兼容旧测试构造器；生产链路由网关负责连接收尾。
                return false;
            }
        };
    }

    private static Map<String, Object> startPayload(String tool, TraceContext.Snapshot trace,
            String inputSummary) {
        Map<String, Object> payload = basePayload(tool, "start", trace);
        payload.put("inputSummary", inputSummary);
        return payload;
    }

    private static Map<String, Object> endPayload(String tool, TraceContext.Snapshot trace,
            long durationMs, String inputSummary, String outputSummary, int outputLength, String error) {
        Map<String, Object> payload = basePayload(tool, error == null ? "end" : "failed", trace);
        payload.put("durationMs", durationMs);
        payload.put("inputSummary", inputSummary);
        payload.put("outputSummary", outputSummary);
        payload.put("outputLength", outputLength);
        if (error != null) {
            payload.put("error", error);
        }
        return payload;
    }

    private static Map<String, Object> basePayload(String tool, String phase, TraceContext.Snapshot trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool);
        payload.put("phase", phase);
        payload.put("traceId", trace.traceId());
        if (trace.agentId() != null) {
            payload.put("agentId", trace.agentId());
        }
        if (trace.role() != null) {
            payload.put("role", trace.role());
        }
        if (trace.skillId() != null) {
            payload.put("skillId", trace.skillId());
            payload.put("skillVersion", trace.skillVersion());
            payload.put("skillPermissions", trace.skillPermissions());
        }
        return payload;
    }
}
