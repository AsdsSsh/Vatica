package com.example.vatica.task;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.vatica.permission.PermissionEventPublisher;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.TenantChannels;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务进度事件发布（迭代 7 I7-1）：按任务 id 维护 SSE 订阅者注册表，
 * 执行器在状态/步骤流转时广播快照事件（前端步骤面板实时打勾）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>订阅即回放</b>：subscribe 时先推一条当前快照——前端"先订阅后审批"的时序下
 *       不会漏掉早期事件</li>
 *   <li>事件负载 = 任务完整快照（含计划 JSON），前端收到即整页重渲染，
 *       事件顺序由单个 HTTP 连接保证，无需客户端排序</li>
 *   <li>断连/超时清理：订阅者收尾时从注册表移除（复用迭代 2.5 活跃连接注册表思路）</li>
 * </ul>
 */
@Component
public class TaskEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskEventPublisher.class);

    private static final long SUBSCRIBER_TIMEOUT_MS = 5 * 60 * 1000L;

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final PermissionEventPublisher permissionEvents;

    public TaskEventPublisher(ObjectMapper mapper, PermissionEventPublisher permissionEvents) {
        this.mapper = mapper;
        this.permissionEvents = permissionEvents;
    }

    /** 订阅任务进度：立即回放当前快照，随后持续推送；同时订阅同 channel 的文件权限事件（迭代 11）。 */
    public SseEmitter subscribe(TaskRecord record) {
        String channel = channel(record);
        SseEmitter emitter = new SseEmitter(SUBSCRIBER_TIMEOUT_MS);
        Set<SseEmitter> set = subscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        permissionEvents.subscribe(channel, emitter);
        emitter.onTimeout(() -> remove(channel, emitter));
        emitter.onError(e -> remove(channel, emitter));
        emitter.onCompletion(() -> remove(channel, emitter));
        try {
            emitter.send(SseEmitter.event().name("task").data(toJson(snapshot(record, "snapshot"))));
        } catch (IOException e) {
            remove(channel, emitter);
        }
        return emitter;
    }

    /** 广播任务快照（无订阅者时零开销）。 */
    public void publish(TaskRecord record, String type) {
        String channel = channel(record);
        Set<SseEmitter> set = subscribers.get(channel);
        if (set == null || set.isEmpty()) {
            return;
        }
        String json = toJson(snapshot(record, type));
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("task").data(json));
            } catch (Exception e) {
                remove(channel, emitter);
            }
        }
    }

    private Map<String, Object> snapshot(TaskRecord r, String type) {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("type", type);
        snap.put("id", r.getId());
        snap.put("goal", r.getGoal());
        snap.put("status", r.getStatus().name());
        // 迭代 9 I9-3：补 createdAt——事件负载与任务详情接口完全同构（前端一个类型两处复用）
        snap.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        snap.put("currentStep", r.getCurrentStep());
        snap.put("pendingStepId", r.getPendingStepId());
        snap.put("score", r.getScore());
        snap.put("verdict", r.getVerdict() == null ? null : r.getVerdict().name());
        snap.put("reworkCount", r.getReworkCount());
        snap.put("error", r.getError());
        snap.put("recoverable", r.isRecoverable());
        try {
            snap.put("plan", mapper.readValue(r.getPlanJson(), Object.class));
        } catch (Exception e) {
            snap.put("plan", List.of());
        }
        return snap;
    }

    private String toJson(Map<String, Object> snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            log.warn("任务事件序列化失败", e);
            return "{}";
        }
    }

    private void remove(String channel, SseEmitter emitter) {
        Set<SseEmitter> set = subscribers.get(channel);
        if (set != null) {
            set.remove(emitter);
        }
        permissionEvents.unsubscribe(channel, emitter);
    }

    private static String channel(TaskRecord record) {
        return TenantChannels.task(new RequestIdentity(record.getUserId(), record.getOrgId(),
                "TASK_OWNER", "task-owner"), record.getId());
    }
}
