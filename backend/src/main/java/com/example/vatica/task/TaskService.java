package com.example.vatica.task;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务编排（迭代 5 I5-3）：创建（Planner）→ 审批（HITL）→ 逐步执行（Executor）→ 状态机流转 → 持久化。
 *
 * <p>审批点有两级（面试核心：<b>HITL 是执行前人工审批，管"能不能干"</b>）：
 * <ol>
 *   <li>计划审批：PENDING → RUNNING（用户确认拆解结果后才开始执行）</li>
 *   <li>敏感步骤审批：执行到 needsApproval 步骤挂起 PENDING_APPROVAL → 用户批准后继续</li>
 * </ol>
 * REVIEW 段本迭代为占位（自动转 DONE），迭代 5.5 接入 Judge 评分后分流 RETRY / NEEDS_REVISION。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final TaskRecordRepository repository;
    private final ObjectMapper mapper;

    public TaskService(PlannerAgent plannerAgent, ExecutorAgent executorAgent,
            TaskRecordRepository repository, ObjectMapper mapper) {
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.repository = repository;
        this.mapper = mapper;
    }

    /** 创建任务：Planner 拆解 → PENDING 待审批计划。 */
    @Transactional
    public TaskRecord create(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("操作失败：任务目标不能为空。");
        }
        TaskPlan plan = plannerAgent.plan(goal.trim());
        TaskRecord record = new TaskRecord(UUID.randomUUID().toString(), goal.trim(),
                TaskStatus.PENDING, toJson(plan), 0);
        return repository.save(record);
    }

    /** 查询单任务。 */
    public TaskRecord get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("操作失败：任务不存在（id=" + id + "）。"));
    }

    /** 最近任务（前端任务列表用）。 */
    public List<TaskRecord> recent(int limit) {
        return repository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(Math.min(Math.max(limit, 1), 100))
                .toList();
    }

    /**
     * 审批：PENDING=批准计划并开始执行；PENDING_APPROVAL=批准挂起步骤并继续执行。
     * 同步执行到下一个审批点或任务终态后返回。
     */
    @Transactional
    public TaskRecord approve(String id) {
        TaskRecord record = get(id);
        TaskStatus from = record.getStatus();
        switch (from) {
            case PENDING -> {
                TaskStateMachine.requireTransition(from, TaskStatus.RUNNING);
                record.setStatus(TaskStatus.RUNNING);
                repository.save(record);
            }
            case PENDING_APPROVAL -> {
                TaskStateMachine.requireTransition(from, TaskStatus.RUNNING);
                TaskPlan plan = parse(record.getPlanJson());
                TaskStep pending = findStep(plan, record.getPendingStepId());
                pending.setApproved(true); // 批准后跳过该审批点
                record.setPendingStepId(-1);
                record.setPlanJson(toJson(plan));
                record.setStatus(TaskStatus.RUNNING);
                repository.save(record);
            }
            default -> throw new IllegalArgumentException(
                    "操作失败：当前状态（" + from + "）不需要审批。");
        }
        executeUntilBlocked(record);
        return repository.save(record);
    }

    /** 从 currentStep 逐步执行：遇审批点挂起；全部完成 → REVIEW → DONE；异常 → FAILED。 */
    private void executeUntilBlocked(TaskRecord record) {
        TaskPlan plan = parse(record.getPlanJson());
        List<String> previous = new ArrayList<>();
        for (int i = 0; i < record.getCurrentStep(); i++) {
            String result = plan.getSteps().get(i).getResult();
            previous.add(result == null ? "" : result);
        }

        for (int i = record.getCurrentStep(); i < plan.getSteps().size(); i++) {
            TaskStep step = plan.getSteps().get(i);
            if (step.isNeedsApproval() && !step.isApproved()) {
                TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.PENDING_APPROVAL);
                record.setStatus(TaskStatus.PENDING_APPROVAL);
                record.setPendingStepId(step.getId());
                record.setCurrentStep(i);
                repository.save(record);
                log.info("任务 {} 步骤 {} 命中审批点，挂起等待人工审批", record.getId(), step.getId());
                return;
            }
            try {
                String result = executorAgent.executeStep(record.getGoal(), step, previous);
                step.setResult(result == null ? "" : result);
                record.setPlanJson(toJson(plan));
                record.setCurrentStep(i + 1);
                previous.add(step.getResult());
                repository.save(record);
            } catch (Exception e) {
                log.error("任务 {} 步骤 {} 执行失败", record.getId(), step.getId(), e);
                TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.FAILED);
                record.setStatus(TaskStatus.FAILED);
                record.setError("步骤 " + step.getId() + " 执行失败：" + e.getMessage());
                return;
            }
        }
        // 全部完成：REVIEW 占位（迭代 5.5 换 Judge 分流），当前自动交付
        TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.REVIEW);
        record.setStatus(TaskStatus.REVIEW);
        record.setCurrentStep(-1);
        record.setPendingStepId(-1);
        TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.DONE);
        record.setStatus(TaskStatus.DONE);
        log.info("任务 {} 执行完成，交付", record.getId());
    }

    private TaskPlan parse(String json) {
        try {
            return mapper.readValue(json, TaskPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：任务计划数据损坏。" + e.getMessage(), e);
        }
    }

    private String toJson(TaskPlan plan) {
        try {
            return mapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：计划序列化失败。" + e.getMessage(), e);
        }
    }

    private static TaskStep findStep(TaskPlan plan, int stepId) {
        return plan.getSteps().stream()
                .filter(s -> s.getId() == stepId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：计划中找不到步骤 id=" + stepId));
    }
}
