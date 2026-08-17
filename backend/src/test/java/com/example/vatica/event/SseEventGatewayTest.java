package com.example.vatica.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 16 I16-1/I16-3：统一 SSE 网关的订阅、发布与回放。 */
class SseEventGatewayTest {

    @Test
    void publishesToActiveSubscriber() {
        SseEventGateway gateway = new SseEventGateway(new ObjectMapper());
        SseEmitter emitter = gateway.subscribe("chat:test", null, Duration.ofMinutes(1));

        assertThat(gateway.subscriberCount("chat:test")).isOne();
        assertThat(gateway.publish("chat:test", "chat_text", "hello")).isTrue();

        emitter.complete();
        gateway.shutdown();
    }

    @Test
    void keepsEventsForLastEventIdReplayAndSendsInitialSnapshotOnlyOnce() {
        SseEventGateway gateway = new SseEventGateway(new ObjectMapper());
        assertThat(gateway.publish("task:test", "task_snapshot", java.util.Map.of("status", "RUNNING")))
                .isFalse();
        assertThat(gateway.publish("task:test", "task_snapshot", java.util.Map.of("status", "DONE")))
                .isFalse();

        SseEmitter replay = gateway.subscribe("task:test", "1", Duration.ofMinutes(1));
        assertThat(gateway.subscriberCount("task:test")).isOne();
        replay.complete();

        SseEmitter initial = gateway.subscribe("task:initial", null, Duration.ofMinutes(1),
                new SseEventGateway.InitialEvent("task_snapshot", java.util.Map.of("status", "DONE")));
        assertThat(gateway.subscriberCount("task:initial")).isOne();
        initial.complete();
        gateway.shutdown();
    }
}
