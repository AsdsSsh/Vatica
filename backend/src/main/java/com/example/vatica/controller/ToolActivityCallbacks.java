package com.example.vatica.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 聊天工具活动包装（迭代 12 I12-4）：在每个工具调用前后向聊天 SSE 发
 * {@code tool_activity} 事件（start / end / failed + 工具名 + 耗时），
 * 前端对话区渲染"正在读取文件…"活动胶囊——失败只上报、不打断主回复。
 */
public final class ToolActivityCallbacks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolCallback[] wrap(ToolCallback[] callbacks, SseEmitter emitter) {
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = wrapOne(callbacks[i], emitter);
        }
        return wrapped;
    }

    private static ToolCallback wrapOne(ToolCallback delegate, SseEmitter emitter) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                String tool = delegate.getToolDefinition().name();
                long start = System.nanoTime();
                send(emitter, Map.of("tool", tool, "phase", "start"));
                try {
                    String out = delegate.call(toolInput);
                    send(emitter, payload(tool, "end", start, null));
                    return out;
                } catch (RuntimeException e) {
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    send(emitter, payload(tool, "failed", start, message.substring(0, Math.min(160, message.length()))));
                    throw e;
                }
            }
        };
    }

    private static Map<String, Object> payload(String tool, String phase, long startNanos, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool);
        payload.put("phase", phase);
        payload.put("durationMs", (System.nanoTime() - startNanos) / 1_000_000);
        if (error != null) {
            payload.put("error", error);
        }
        return payload;
    }

    private static void send(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name("tool_activity").data(MAPPER.writeValueAsString(payload)));
        } catch (IOException e) {
            // 客户端已断连：连接收尾由 ChatController 的 send 失败路径统一处理，这里不重复抛
        }
    }
}
