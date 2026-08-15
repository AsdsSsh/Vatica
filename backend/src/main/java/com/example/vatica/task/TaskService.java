package com.example.vatica.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.agent.JudgeAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.config.JudgeProperties;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务编排（迭代 5 I5-3；迭代 5.5 I5.5-2/3；迭代 6 I6-1 多 Agent 并行）：创建（Planner）→ 审批（HITL）
 * → 波次执行（WaveScheduler 拓扑分层 + 虚拟线程 CompletableFuture 并行）→ 评测（Judge）→ 状态机流转 → 持久化。
 *
 * <p>审批点有两级（面试核心：<b>HITL 是执行前人工审批，管"能不能干"</b>）：
 * <ol>
 *   <li>计划审批：PENDING → RUNNING（用户确认拆解结果后才开始执行）</li>
 *   <li>敏感步骤审批：执行到 needsApproval 步骤挂起 PENDING_APPROVAL → 用户批准后继续
 *       （迭代 6 起作为并行波次的屏障：审批步骤独占一波，不允许并行绕过）</li>
 * </ol>
 * 执行完进入 REVIEW 段（迭代 5.5，质量门禁管"干得好不好"）：Judge PASS → DONE；
 * Judge FAIL → 自动返工 RETRY（限 max-auto-rework 次）→ 超限 NEEDS_REVISION 交人工。
 * 人工返工入口 {@link #rework}（DONE / NEEDS_REVISION 均可，返工需重新审批副作用步骤）。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final JudgeAgent judgeAgent;
    private final JudgeProperties judgeProps;
    private final TaskRecordRepository repository;
    private final ObjectMapper mapper;
    private final Executor parallelExecutor;
    private final TaskEventPublisher eventPublisher;

    /** 终止标志（迭代 7 I7-4）：取消接口与执行线程的协作式协调点（波次粒度生效）。 */
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 用于跨事务重读被终止任务的最新状态（需悲观锁"当前读"，避开 REPEATABLE_READ 快照）。 */
    @PersistenceContext
    private EntityManager entityManager;

    public TaskService(PlannerAgent plannerAgent, ExecutorAgent executorAgent, JudgeAgent judgeAgent,
            JudgeProperties judgeProps, TaskRecordRepository repository, ObjectMapper mapper,
            @Qualifier("taskParallelExecutor") Executor parallelExecutor, TaskEventPublisher eventPublisher) {
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.judgeAgent = judgeAgent;
        this.judgeProps = judgeProps;
        this.repository = repository;
        this.mapper = mapper;
        this.parallelExecutor = parallelExecutor;
        this.eventPublisher = eventPublisher;
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
                .orElseThrow(() -> new TaskNotFoundException(id));
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
        // 执行期间被终止：状态已由 cancel() 事务提交为 CANCELLED。
        // MySQL REPEATABLE_READ 下普通 refresh 是快照读（读到本事务起始的旧状态），
        // 必须用悲观锁做"当前读"才能拿到 CANCELLED；实体刷新后与库一致（干净态），
        // 提交时不会把旧状态覆盖回库
        if (isCancelled(record.getId())) {
            entityManager.refresh(record, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        }
        return repository.save(record);
    }

    /**
     * 用户终止任务（迭代 7 I7-4）：PENDING / RUNNING / PENDING_APPROVAL 可终止 → CANCELLED（终态）。
     * 运行中的执行线程经终止标志协作式感知（波次粒度生效），不回写旧状态。
     */
    @Transactional
    public TaskRecord cancel(String id) {
        TaskRecord record = get(id);
        switch (record.getStatus()) {
            case PENDING, RUNNING, PENDING_APPROVAL -> {
                TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.CANCELLED);
                record.setStatus(TaskStatus.CANCELLED);
                record.setError("用户手动终止");
                cancelFlags.computeIfAbsent(id, k -> new AtomicBoolean()).set(true);
                repository.save(record);
                eventPublisher.publish(record, "cancelled");
                log.info("任务 {} 被用户终止", id);
            }
            default -> throw new IllegalArgumentException(
                    "操作失败：当前状态（" + record.getStatus() + "）不允许终止。");
        }
        return record;
    }

    /**
     * 人工返工（迭代 5.5 I5.5-3）：DONE（已交付想重做）或 NEEDS_REVISION（评测不合格交人工）均可发起。
     * 清空步骤结果与审批标记后重跑（副作用步骤需重新审批=可重入设计：已发邮件等副作用由人工确认是否重放）；
     * 重置自动返工窗口（人工介入开启新一轮评测循环）。同步执行到下一个审批点或终态后返回。
     */
    @Transactional
    public TaskRecord rework(String id) {
        TaskRecord record = get(id);
        switch (record.getStatus()) {
            case NEEDS_REVISION -> {
                TaskStateMachine.requireTransition(TaskStatus.NEEDS_REVISION, TaskStatus.RUNNING);
                record.setStatus(TaskStatus.RUNNING);
            }
            case DONE -> {
                TaskStateMachine.requireTransition(TaskStatus.DONE, TaskStatus.RETRY);
                record.setStatus(TaskStatus.RETRY);
                TaskStateMachine.requireTransition(TaskStatus.RETRY, TaskStatus.RUNNING);
                record.setStatus(TaskStatus.RUNNING);
            }
            default -> throw new IllegalArgumentException(
                    "操作失败：当前状态（" + record.getStatus() + "）不允许返工，仅 DONE / NEEDS_REVISION 可返工。");
        }
        TaskPlan plan = parse(record.getPlanJson());
        resetPlanForRerun(record, plan);
        record.setReworkCount(0);   // 人工返工重置自动返工窗口（新一轮评测循环）
        record.setScore(null);
        record.setVerdict(null);
        record.setError(null);
        repository.save(record);
        executeUntilBlocked(record);
        return repository.save(record);
    }

    /** 从 currentStep 波次执行（迭代 6 并行）：遇审批点挂起；全部完成 → REVIEW 评测段；异常 → FAILED。 */
    private void executeUntilBlocked(TaskRecord record) {
        TaskPlan plan = parse(record.getPlanJson());
        List<List<Integer>> waves = WaveScheduler.waves(plan);

        for (List<Integer> wave : waves) {
            List<Integer> todo = wave.stream().filter(i -> i >= record.getCurrentStep()).toList();
            if (todo.isEmpty()) {
                continue;
            }
            if (isCancelled(record.getId())) {
                return;   // 已被终止：状态已由 cancel() 落 CANCELLED，本线程不回写
            }
            // 审批点屏障：未批准的审批步骤独占一波（WaveScheduler 保证），挂起等待人工
            if (todo.size() == 1) {
                int idx = todo.get(0);
                TaskStep step = plan.getSteps().get(idx);
                if (step.isNeedsApproval() && !step.isApproved()) {
                    TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.PENDING_APPROVAL);
                    record.setStatus(TaskStatus.PENDING_APPROVAL);
                    record.setPendingStepId(step.getId());
                    record.setCurrentStep(idx);
                    repository.save(record);
                    eventPublisher.publish(record, "approval_required");
                    log.info("任务 {} 步骤 {} 命中审批点，挂起等待人工审批", record.getId(), step.getId());
                    return;
                }
            }
            // 并行执行本波：虚拟线程 + CompletableFuture（每步骤一个 Worker Agent）
            List<String> previous = previousResults(plan, record.getCurrentStep());
            List<CompletableFuture<String>> futures = new ArrayList<>();
            try {
                eventPublisher.publish(record, "step_running");
                for (int idx : todo) {
                    TaskStep step = plan.getSteps().get(idx);
                    futures.add(CompletableFuture
                            .supplyAsync(() -> execute(record, step, previous), parallelExecutor));
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                if (isCancelled(record.getId())) {
                    futures.forEach(f -> f.cancel(true));
                    return;   // 终止在波执行期间生效：不落进度，状态保持 CANCELLED（防旧状态覆盖）
                }
                for (int k = 0; k < todo.size(); k++) {
                    plan.getSteps().get(todo.get(k)).setResult(futures.get(k).join());
                }
                record.setPlanJson(toJson(plan));
                record.setCurrentStep(todo.get(todo.size() - 1) + 1);
                repository.save(record);
                eventPublisher.publish(record, "step_done");
                log.info("任务 {} 波次完成：步骤下标 {}（{} 个并行）", record.getId(), todo, todo.size());
            } catch (Exception e) {
                futures.forEach(f -> f.cancel(true));
                if (isCancelled(record.getId())) {
                    return;   // 终止引发的执行中断：不覆盖 CANCELLED
                }
                Throwable cause = e instanceof CompletionException ? e.getCause() : e;
                log.error("任务 {} 波次执行失败（步骤 {}）", record.getId(), todo, cause);
                TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.FAILED);
                record.setStatus(TaskStatus.FAILED);
                record.setError(cause.getMessage());
                cancelFlags.remove(record.getId());
                repository.save(record);
                eventPublisher.publish(record, "failed");
                return;
            }
        }
        // 全部完成：进入 REVIEW 评测段（迭代 5.5：Judge 评分分流）
        if (isCancelled(record.getId())) {
            return;
        }
        TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.REVIEW);
        record.setStatus(TaskStatus.REVIEW);
        record.setCurrentStep(-1);
        record.setPendingStepId(-1);
        repository.save(record);
        eventPublisher.publish(record, "review");
        evaluateAndRoute(record, plan);
    }

    /** 单步骤执行包装：异常附步骤号（并行波中定位失败来源）；supplyAsync 会再包一层 CompletionException。 */
    private String execute(TaskRecord record, TaskStep step, List<String> previous) {
        try {
            return executorAgent.executeStep(record.getGoal(), step, previous);
        } catch (RuntimeException e) {
            throw new IllegalStateException("步骤 " + step.getId() + " 执行失败：" + e.getMessage(), e);
        }
    }

    /** 当前步之前所有已完成步骤的结果（按序，波内并行步骤共享的只读上下文）。 */
    private static List<String> previousResults(TaskPlan plan, int currentStep) {
        List<String> previous = new ArrayList<>();
        for (int i = 0; i < currentStep; i++) {
            String result = plan.getSteps().get(i).getResult();
            previous.add(result == null ? "" : result);
        }
        return previous;
    }

    /** REVIEW 段：Judge 评分 → PASS 交付 DONE；FAIL 自动返工（限次）或超限 NEEDS_REVISION；评测异常 FAILED。 */
    private void evaluateAndRoute(TaskRecord record, TaskPlan plan) {
        if (isCancelled(record.getId())) {
            return;   // 评测前/中被终止：状态保持 CANCELLED
        }
        JudgeAgent.Evaluation eval;
        try {
            eval = judgeAgent.evaluate(record.getGoal(), plan);
        } catch (Exception e) {
            log.error("任务 {} 评测异常", record.getId(), e);
            TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.FAILED);
            record.setStatus(TaskStatus.FAILED);
            record.setError("评测异常：" + e.getMessage());
            cancelFlags.remove(record.getId());
            repository.save(record);
            eventPublisher.publish(record, "failed");
            return;
        }
        record.setScore(eval.score());
        record.setVerdict(eval.verdict());
        if (isCancelled(record.getId())) {
            return;   // 评测返回瞬间被终止：不落评测结果
        }
        if (eval.verdict() == TaskVerdict.PASS) {
            TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.DONE);
            record.setStatus(TaskStatus.DONE);
            cancelFlags.remove(record.getId());
            log.info("任务 {} 评测通过（{} 分），交付", record.getId(), eval.score());
            repository.save(record);
            eventPublisher.publish(record, "done");
        } else if (record.getReworkCount() >= judgeProps.maxAutoRework()) {
            TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.NEEDS_REVISION);
            record.setStatus(TaskStatus.NEEDS_REVISION);
            record.setError("评测不合格（" + eval.score() + " 分）：" + eval.summary()
                    + "。自动返工已达上限 " + judgeProps.maxAutoRework() + " 次，请人工返工。");
            cancelFlags.remove(record.getId());
            log.info("任务 {} 评测不合格且自动返工超限，转人工", record.getId());
            repository.save(record);
            eventPublisher.publish(record, "needs_revision");
        } else {
            TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.RETRY);
            record.setStatus(TaskStatus.RETRY);
            record.setReworkCount(record.getReworkCount() + 1);
            resetPlanForRerun(record, plan);
            repository.save(record);
            eventPublisher.publish(record, "retry");
            TaskStateMachine.requireTransition(TaskStatus.RETRY, TaskStatus.RUNNING);
            record.setStatus(TaskStatus.RUNNING);
            log.info("任务 {} 评测不合格（{} 分），第 {} 次自动返工", record.getId(),
                    eval.score(), record.getReworkCount());
            executeUntilBlocked(record);   // 递归重跑，深度由 max-auto-rework 兜底
        }
    }

    /** 是否已被用户终止（取消接口与执行线程的协作式协调点）。 */
    private boolean isCancelled(String taskId) {
        AtomicBoolean flag = cancelFlags.get(taskId);
        return flag != null && flag.get();
    }

    /** 返工重跑前重置计划：清步骤结果、撤审批标记（副作用步骤需重新审批=可重入设计）。 */
    private void resetPlanForRerun(TaskRecord record, TaskPlan plan) {
        for (TaskStep step : plan.getSteps()) {
            step.setResult(null);
            step.setApproved(false);
        }
        record.setPlanJson(toJson(plan));
        record.setCurrentStep(0);
        record.setPendingStepId(-1);
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
