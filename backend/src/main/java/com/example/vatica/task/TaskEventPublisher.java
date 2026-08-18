package com.example.vatica.task;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.event.SseEventGateway;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务进度事件发布（迭代 7 I7-1；迭代 16 I16-2/I16-3）：按任务 channel
 * 发布统一 SSE 快照事件，订阅和回放交给 {@link SseEventGateway}。
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

    private static final long SUBSCRIBER_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ObjectMapper mapper;
    private final SseEventGateway gateway;

    @Autowired
    public TaskEventPublisher(ObjectMapper mapper, SseEventGateway gateway) {
        this.mapper = mapper;
        this.gateway = gateway;
    }

    /** 订阅任务进度：首次订阅回放当前快照，重连按 Last-Event-ID 回放缺失事件。 */
    public SseEmitter subscribe(TaskRecord record, String lastEventId) {
        String channel = channel(record);
        SseEventGateway.InitialEvent initial = lastEventId == null || lastEventId.isBlank()
                ? new SseEventGateway.InitialEvent("task_snapshot", snapshot(record, "snapshot"))
                : null;
        return gateway.subscribe(channel, lastEventId, Duration.ofMillis(SUBSCRIBER_TIMEOUT_MS), initial);
    }

    /** 广播任务快照（无订阅者时零开销）。 */
    public void publish(TaskRecord record, String type) {
        String channel = channel(record);
        gateway.publish(channel, "task_snapshot", snapshot(record, type));
    }

    /** 迭代 17B：四原语使用独立统一事件，随后仍发布任务快照供断线回放后的完整重建。 */
    public void publishBlackboard(TaskRecord record, BlackboardEntry entry) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("taskId", record.getId());
        data.put("entry", entry);
        gateway.publish(channel(record), "blackboard_entry", data);
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

    private static String channel(TaskRecord record) {
        return TenantChannels.task(new RequestIdentity(record.getUserId(), record.getOrgId(),
                "TASK_OWNER", "task-owner"), record.getId());
    }
}
