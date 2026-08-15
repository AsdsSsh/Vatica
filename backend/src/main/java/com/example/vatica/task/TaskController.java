package com.example.vatica.task;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务接口（迭代 5 I5-3；迭代 5.5 返工；迭代 7 终止 + 步骤级进度事件）：
 * 创建任务（一句话）→ 审批计划/步骤 → 查询进度 → 人工返工 → 终止 → SSE 订阅进度。
 *
 * <p>注意（已知边界）：审批接口<b>同步</b>执行到下一个审批点或终态才返回；
 * 步骤级实时进度经 {@code GET /{id}/events}（SSE）推送，订阅即回放当前快照。
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final TaskService taskService;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper mapper;

    public TaskController(TaskService taskService, TaskEventPublisher eventPublisher, ObjectMapper mapper) {
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
        this.mapper = mapper;
    }

    /** 一句话创建任务：Planner 拆解 → 返回计划（PENDING 待审批）。 */
    @PostMapping
    public TaskDetailDto create(@RequestBody Map<String, String> body) {
        TaskRecord record = taskService.create(body.getOrDefault("goal", ""));
        return detail(record);
    }

    /** 审批（计划或挂起步骤）并同步推进执行。 */
    @PostMapping("/{id}/approve")
    public TaskDetailDto approve(@PathVariable String id) {
        return detail(taskService.approve(id));
    }

    /** 人工返工（迭代 5.5）：DONE（想重做）或 NEEDS_REVISION（评测不合格）→ 重跑并同步推进。 */
    @PostMapping("/{id}/rework")
    public TaskDetailDto rework(@PathVariable String id) {
        return detail(taskService.rework(id));
    }

    /** 用户终止（迭代 7 I7-4）：PENDING/RUNNING/PENDING_APPROVAL → CANCELLED。 */
    @PostMapping("/{id}/cancel")
    public TaskDetailDto cancel(@PathVariable String id) {
        return detail(taskService.cancel(id));
    }

    /** 步骤级进度事件（迭代 7 I7-1）：SSE 订阅任务进度，订阅即回放当前快照。 */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        return eventPublisher.subscribe(taskService.get(id));
    }

    /** 单任务详情。 */
    @GetMapping("/{id}")
    public TaskDetailDto get(@PathVariable String id) {
        return detail(taskService.get(id));
    }

    /** 最近任务列表。 */
    @GetMapping
    public List<TaskSummaryDto> list() {
        return taskService.recent(20).stream().map(this::summary).toList();
    }

    private TaskSummaryDto summary(TaskRecord r) {
        return new TaskSummaryDto(r.getId(), r.getGoal(), r.getStatus().name(),
                r.getCreatedAt().toString());
    }

    /** 详情与 SSE 事件负载同构（事件 = 详情 + type 字段），前端一个类型两处复用。 */
    private TaskDetailDto detail(TaskRecord r) {
        Object plan;
        try {
            plan = mapper.readValue(r.getPlanJson(), Object.class);
        } catch (Exception e) {
            plan = "（计划数据不可读）";
        }
        return new TaskDetailDto(r.getId(), r.getGoal(), r.getStatus().name(),
                r.getCreatedAt().toString(), r.getCurrentStep(), r.getPendingStepId(),
                r.getScore(), r.getVerdict() == null ? null : r.getVerdict().name(),
                r.getReworkCount(), r.getError(), plan);
    }
}
