package com.example.vatica.event;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一 SSE 事件网关（迭代 16 I16-1/I16-2/I16-3）。
 *
 * <p>所有实时事件都使用同一个信封：{@code {id,type,data,ts}}。每个 channel
 * 保留一个有界事件环，订阅时可使用 {@code Last-Event-ID} 回放缺失事件；任务流
 * 另外支持订阅首次快照，避免客户端在刚打开面板时漏掉状态。</p>
 *
 * <p>当前实现为单实例内存网关。事件 ID 使用单调递增序列，后续接 Redis 时只需
 * 替换发布/回放存储，不改变 HTTP/SSE 契约。</p>
 */
@Component
public class SseEventGateway {

    private static final int HISTORY_LIMIT = 256;

    private final ObjectMapper mapper;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, ChannelState> channels = new ConcurrentHashMap<>();
    /** 心跳只负责保持长连接，不占用事件序号，也不会进入回放环。 */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "vatica-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public SseEventGateway(ObjectMapper mapper) {
        this.mapper = mapper;
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /** 统一事件信封；data 保持原业务 DTO 形状，避免前端再拆一层业务包装。 */
    public record EventEnvelope(String id, String type, Object data, Instant ts) {
    }

    /** 订阅首次需要发送的业务快照。 */
    public record InitialEvent(String type, Object data) {
    }

    private static final class ChannelState {
        private final Deque<EventEnvelope> history = new ArrayDeque<>();
        private final Map<SseEmitter, Subscription> subscribers = new LinkedHashMap<>();
    }

    private record Subscription(SseEmitter emitter, Runnable close) {
    }

    private record ReplayBatch(ArrayList<EventEnvelope> events, boolean gap) {
    }

    /** 订阅事件流，不附带首个快照。 */
    public SseEmitter subscribe(String channel, String lastEventId, Duration timeout) {
        return subscribe(channel, lastEventId, timeout, null);
    }

    /**
     * 订阅事件流：先回放 lastEventId 之后的事件，再发送可选首个快照。
     * 首个快照由调用方控制，重连时传 null 可避免重复渲染当前状态。
     */
    public SseEmitter subscribe(String channel, String lastEventId, Duration timeout,
            InitialEvent initial) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        ChannelState state = channels.computeIfAbsent(channel, ignored -> new ChannelState());
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                remove(channel, emitter);
            }
        };
        emitter.onTimeout(close);
        emitter.onError(ignored -> close.run());
        emitter.onCompletion(close);

        synchronized (state) {
            Subscription subscription = new Subscription(emitter, close);
            state.subscribers.put(emitter, subscription);
            try {
                // 没有续传游标表示一次全新的业务订阅：聊天不能重复旧回复，任务只发当前快照。
                boolean replayGap = false;
                if (lastEventId != null && !lastEventId.isBlank()) {
                    ReplayBatch replay = replayAfter(state.history, lastEventId);
                    replayGap = replay.gap();
                    for (EventEnvelope event : replay.events()) {
                        send(emitter, event);
                    }
                }
                // 事件环已淘汰游标或服务重启丢失历史时，快照是唯一可靠的重建方式。
                if (initial != null && ((lastEventId == null || lastEventId.isBlank()) || replayGap)) {
                    EventEnvelope snapshot = next(initial.type(), initial.data());
                    append(state.history, snapshot);
                    send(emitter, snapshot);
                }
            } catch (IOException | IllegalStateException e) {
                close.run();
            }
        }
        return emitter;
    }

    /** 发布事件并写入有界回放环；没有订阅者时不进行网络写入。 */
    public boolean publish(String channel, String type, Object data) {
        return publish(channel, type, data, true);
    }

    /**
     * 发布不可回放事件。权限请求具有一次性状态，断连时会由 channel cleanup 取消，
     * 因此不能在重连后重新弹出已经失效的审批请求。
     */
    public boolean publishTransient(String channel, String type, Object data) {
        return publish(channel, type, data, false);
    }

    private boolean publish(String channel, String type, Object data, boolean replayable) {
        // 一次性事件无订阅者时不创建状态；可回放事件才需要保留历史供后续续传。
        ChannelState state = replayable
                ? channels.computeIfAbsent(channel, ignored -> new ChannelState())
                : channels.get(channel);
        if (state == null) {
            return false;
        }
        EventEnvelope event = next(type, data);
        boolean delivered = false;
        synchronized (state) {
            if (replayable) {
                append(state.history, event);
            }
            for (Subscription subscription : new ArrayList<>(state.subscribers.values())) {
                try {
                    send(subscription.emitter(), event);
                    delivered = true;
                } catch (IOException | IllegalStateException e) {
                    subscription.close().run();
                }
            }
        }
        return delivered;
    }

    /** 当前 channel 的活跃订阅数，供连接生命周期测试与观测使用。 */
    public int subscriberCount(String channel) {
        ChannelState state = channels.get(channel);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.subscribers.size();
        }
    }

    private EventEnvelope next(String type, Object data) {
        return new EventEnvelope(Long.toString(sequence.incrementAndGet()), type, data, Instant.now());
    }

    private static void append(Deque<EventEnvelope> history, EventEnvelope event) {
        history.addLast(event);
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    /** 供回归测试检查事件环淘汰边界，不暴露为 HTTP API。 */
    boolean replayGap(String channel, String lastEventId) {
        ChannelState state = channels.get(channel);
        if (state == null) {
            return lastEventId != null && !lastEventId.isBlank();
        }
        synchronized (state) {
            return replayAfter(state.history, lastEventId).gap();
        }
    }

    /** 供回归测试检查租户频道只保留自己的事件。 */
    java.util.List<EventEnvelope> history(String channel) {
        ChannelState state = channels.get(channel);
        if (state == null) {
            return java.util.List.of();
        }
        synchronized (state) {
            return java.util.List.copyOf(state.history);
        }
    }

    private static ReplayBatch replayAfter(Deque<EventEnvelope> history, String lastEventId) {
        long last = parseEventId(lastEventId);
        ArrayList<EventEnvelope> replay = new ArrayList<>();
        for (EventEnvelope event : history) {
            if (parseEventId(event.id()) > last) {
                replay.add(event);
            }
        }
        boolean cursorRetained = history.stream()
                .anyMatch(event -> parseEventId(event.id()) == last);
        boolean gap = !cursorRetained;
        return new ReplayBatch(replay, gap);
    }

    private static long parseEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void send(SseEmitter emitter, EventEnvelope event) throws IOException {
        // 使用字符串化时间，兼容测试/嵌入式调用方传入的裸 ObjectMapper（未注册 JavaTimeModule）。
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", event.id());
        envelope.put("type", event.type());
        envelope.put("data", event.data());
        envelope.put("ts", event.ts().toString());
        String json = mapper.writeValueAsString(envelope);
        emitter.send(SseEmitter.event()
                .id(event.id())
                .name(event.type())
                .data(json));
    }

    private void sendHeartbeats() {
        for (Map.Entry<String, ChannelState> entry : channels.entrySet()) {
            ChannelState state = entry.getValue();
            synchronized (state) {
                for (Subscription subscription : new ArrayList<>(state.subscribers.values())) {
                    try {
                        subscription.emitter().send(SseEmitter.event().comment("keepalive"));
                    } catch (IOException | IllegalStateException e) {
                        subscription.close().run();
                    }
                }
            }
        }
    }

    private void remove(String channel, SseEmitter emitter) {
        ChannelState state = channels.get(channel);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.subscribers.remove(emitter);
        }
    }
}
