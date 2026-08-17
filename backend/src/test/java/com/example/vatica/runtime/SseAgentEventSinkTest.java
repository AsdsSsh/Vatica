package com.example.vatica.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 15 I15-19：AgentEventSink → SSE agent_event 信封。 */
class SseAgentEventSinkTest {

    @Test
    void emitsAgentEventEnvelope() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        SseAgentEventSink sink = new SseAgentEventSink(emitter, new ObjectMapper());

        sink.emit("trace", Map.of("tool", "calculator"));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }
}
