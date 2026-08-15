package com.example.vatica.permission;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 文件权限请求 SSE 广播（迭代 11）：按 channel（taskId / sessionId）维护订阅者。
 * 任务事件流与聊天流在建立时订阅对应 channel；权限请求产生时向该 channel 推送。
 */
@Component
public class PermissionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PermissionEventPublisher.class);

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public PermissionEventPublisher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void subscribe(String channel, SseEmitter emitter) {
        subscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(emitter);
    }

    public void unsubscribe(String channel, SseEmitter emitter) {
        Set<SseEmitter> set = subscribers.get(channel);
        if (set != null) {
            set.remove(emitter);
        }
    }

    /** 推送权限请求；返回是否有订阅者收到（无订阅者时调用方应立即按拒绝处理）。 */
    public boolean publish(FilePermissionRequest request) {
        Set<SseEmitter> set = subscribers.get(request.channel());
        if (set == null || set.isEmpty()) {
            return false;
        }
        String data;
        try {
            data = mapper.writeValueAsString(request);
        } catch (Exception e) {
            log.warn("权限请求序列化失败", e);
            return false;
        }
        boolean delivered = false;
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("permission_request").data(data));
                delivered = true;
            } catch (IOException e) {
                unsubscribe(request.channel(), emitter);
            }
        }
        return delivered;
    }
}
