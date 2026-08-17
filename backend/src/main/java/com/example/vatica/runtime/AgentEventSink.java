package com.example.vatica.runtime;

import java.util.Map;

/** 迭代 15 I15-19：双运行时统一事件出口（迭代 16 会收敛到 SseEventGateway）。 */
public interface AgentEventSink {

    void emit(String type, Map<String, Object> payload);
}
