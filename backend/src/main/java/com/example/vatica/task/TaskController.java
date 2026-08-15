package com.example.vatica.task;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务接口（迭代 5 I5-3）：创建任务（一句话）→ 审批计划/步骤 → 查询进度。
 *
 * <p>注意（已知边界）：审批接口<b>同步</b>执行到下一个审批点或终态才返回；
 * 步骤级实时进度事件（SSE）在迭代 7 前端步骤面板联调时补充，本迭代先打通闭环。
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private final TaskService taskService;
    private final ObjectMapper mapper;

    public TaskController(TaskService taskService, ObjectMapper mapper) {
        this.taskService = taskService;
        this.mapper = mapper;
    }

    /** 一句话创建任务：Planner 拆解 → 返回计划（PENDING 待审批）。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        TaskRecord record = taskService.create(body.getOrDefault("goal", ""));
        return detail(record);
    }

    /** 审批（计划或挂起步骤）并同步推进执行。 */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id) {
        return detail(taskService.approve(id));
    }

    /** 人工返工（迭代 5.5）：DONE（想重做）或 NEEDS_REVISION（评测不合格）→ 重跑并同步推进。 */
    @PostMapping("/{id}/rework")
    public Map<String, Object> rework(@PathVariable String id) {
        return detail(taskService.rework(id));
    }

    /** 单任务详情。 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return detail(taskService.get(id));
    }

    /** 最近任务列表。 */
    @GetMapping
    public List<Map<String, Object>> list() {
        return taskService.recent(20).stream().map(this::summary).toList();
    }

    private Map<String, Object> summary(TaskRecord r) {
        return Map.of("id", r.getId(), "goal", r.getGoal(), "status", r.getStatus().name(),
                "createdAt", r.getCreatedAt().toString());
    }

    private Map<String, Object> detail(TaskRecord r) {
        Map<String, Object> detail = new java.util.HashMap<>(summary(r));
        try {
            detail.put("plan", mapper.readValue(r.getPlanJson(), Object.class));
        } catch (Exception e) {
            detail.put("plan", "（计划数据不可读）");
        }
        // 迭代 5.5：质量闭环字段（前端"执行准确率"展示用）
        detail.put("score", r.getScore());
        detail.put("verdict", r.getVerdict() == null ? null : r.getVerdict().name());
        detail.put("reworkCount", r.getReworkCount());
        if (r.getError() != null) {
            detail.put("error", r.getError());
        }
        return detail;
    }
}
