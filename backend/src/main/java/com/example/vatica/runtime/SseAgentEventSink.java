package com.example.vatica.runtime;

import java.io.IOException;
import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.vatica.event.SseEventGateway;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 15 I15-19：Agent 事件 → 当前 SSE 通道（event: agent_event），供聊天/任务复用。 */
public class SseAgentEventSink implements AgentEventSink {

    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    private final SseEventGateway gateway;
    private final String channel;

    public SseAgentEventSink(SseEmitter emitter, ObjectMapper mapper) {
        this.emitter = emitter;
        this.mapper = mapper;
        this.gateway = null;
        this.channel = null;
    }

    /** 迭代 22D：AgentScope 通过统一网关发布到租户隔离 channel。 */
    public SseAgentEventSink(SseEventGateway gateway, String channel) {
        this.emitter = null;
        this.mapper = null;
        this.gateway = gateway;
        this.channel = channel;
    }

    @Override
    public void emit(String type, Map<String, Object> payload) {
        if (gateway != null) {
            gateway.publish(channel, type, payload);
            return;
        }
        try {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("type", type);
            envelope.putAll(payload);
            emitter.send(SseEmitter.event().name("agent_event").data(mapper.writeValueAsString(envelope)));
        } catch (IOException e) {
            // 客户端断连：连接收尾由调用方统一处理，这里不重复抛
        }
    }
}
