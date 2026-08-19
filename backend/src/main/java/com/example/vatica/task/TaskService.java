package com.example.vatica.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.example.vatica.agent.JudgeAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.agent.HumanAgent;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.config.EphemeralCredential;
import com.example.vatica.config.AgentModelBindingService;
import com.example.vatica.config.JudgeProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.TaskReliabilityProperties;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.evaluation.BenchmarkCase;
import com.example.vatica.evaluation.BenchmarkCatalog;
import com.example.vatica.mail.MailConnectionSettings;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.FilePermissionRequestService;
import com.example.vatica.permission.PermissionBoundToolCallbacks;
import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.runtime.AgentRuntime;
import com.example.vatica.runtime.AgentRuntimeFactory;
import com.example.vatica.runtime.AgentRuntimeProperties;
import com.example.vatica.runtime.AgentToolCatalog;
import com.example.vatica.skill.SkillCatalogService;
import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
import com.example.vatica.trace.ReasoningContext;
import com.example.vatica.trace.TraceContext;
import com.example.vatica.trace.TracedToolCallbacks;
import com.example.vatica.tool.RetryableToolCallbacks;
import com.example.vatica.usage.DirectModelUsageRecorder;
import com.example.vatica.usage.UsageContext;
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
    private final JudgeAgent judgeAgent;
    private final JudgeProperties judgeProps;
    private final TaskRecordRepository repository;
    private final ObjectMapper mapper;
    private final Executor parallelExecutor;
    private final TaskEventPublisher eventPublisher;
    private final AgentToolCatalog agentTools;
    private final FilePermissionRequestService permissionRequests;
    private final ModelRegistry registry;
    private final AgentTraceRecordRepository traceRepository;
    private final TaskBlackboard blackboard;
    private final ContextBudget contextBudget;
    private final AgentRuntimeFactory runtimeFactory;
    private final AgentRegistry agentRegistry;
    private final AgentModelBindingService agentModelBindings;
    private final DirectModelUsageRecorder directUsage;
    private final HumanAgent humanAgent;
    private final TaskReliabilityProperties reliability;
    private final TaskExecutionFaultInjector faultInjector;
    private final BenchmarkCatalog benchmarkCatalog;
    private final SkillCatalogService skillCatalog;

    /** 终止标志（迭代 7 I7-4）：取消接口与执行线程的协作式协调点（波次粒度生效）。 */
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** 迭代 18：同一任务的审批/恢复/返工动作单飞；取消不持有该锁，保证运行中仍可及时终止。 */
    private final Map<String, Object> mutationLocks = new ConcurrentHashMap<>();

    /** 迭代 18：同一用户的创建幂等键在单实例内先串行，数据库唯一索引负责跨实例兜底。 */
    private final Map<Long, Semaphore> idempotencyLocks = new ConcurrentHashMap<>();

    /** 迭代 13 I13-5：EPHEMERAL 任务在运行期的临时凭据（不落库，服务重启即失效）。 */
    private final Map<String, EphemeralCredential> ephemeralCredentials = new ConcurrentHashMap<>();

    /** 仅本次邮件凭据与模型临时凭据同样只驻留内存，不进入任务表。 */
    private final Map<String, MailConnectionSettings> ephemeralMailCredentials = new ConcurrentHashMap<>();

    /** 用于跨事务重读被终止任务的最新状态（需悲观锁"当前读"，避开 REPEATABLE_READ 快照）。 */
    @PersistenceContext
    private EntityManager entityManager;

    public TaskService(PlannerAgent plannerAgent, JudgeAgent judgeAgent,
            JudgeProperties judgeProps, TaskRecordRepository repository, ObjectMapper mapper,
            @Qualifier("taskParallelExecutor") Executor parallelExecutor, TaskEventPublisher eventPublisher,
            AgentToolCatalog agentTools, FilePermissionRequestService permissionRequests,
            ModelRegistry registry, AgentTraceRecordRepository traceRepository, TaskBlackboard blackboard,
            ContextBudget contextBudget, AgentRuntimeFactory runtimeFactory, AgentRegistry agentRegistry,
            DirectModelUsageRecorder directUsage, HumanAgent humanAgent, AgentModelBindingService agentModelBindings,
            TaskReliabilityProperties reliability, TaskExecutionFaultInjector faultInjector,
            BenchmarkCatalog benchmarkCatalog, SkillCatalogService skillCatalog) {
        this.plannerAgent = plannerAgent;
        this.judgeAgent = judgeAgent;
        this.judgeProps = judgeProps;
        this.repository = repository;
        this.mapper = mapper;
        this.parallelExecutor = parallelExecutor;
        this.eventPublisher = eventPublisher;
        this.agentTools = agentTools;
        this.permissionRequests = permissionRequests;
        this.registry = registry;
        this.traceRepository = traceRepository;
        this.blackboard = blackboard;
        this.contextBudget = contextBudget;
        this.runtimeFactory = runtimeFactory;
        this.agentRegistry = agentRegistry;
        this.directUsage = directUsage;
        this.humanAgent = humanAgent;
        this.agentModelBindings = agentModelBindings;
        this.reliability = reliability;
        this.faultInjector = faultInjector;
        this.benchmarkCatalog = benchmarkCatalog;
        this.skillCatalog = skillCatalog;
    }

    /** 创建任务：Planner 拆解 → PENDING 待审批计划。 */
    @Transactional
    public TaskRecord create(String goal) {
        return create(goal, null);
    }

    /** 创建任务（迭代 11：携带前端权限快照）。 */
    @Transactional
    public TaskRecord create(String goal, FilePermissionPolicy permission) {
        return create(goal, permission, null);
    }

    /** 创建任务（迭代 13 I13-5：支持请求级临时凭据，credential 不落库）。 */
    @Transactional
    public TaskRecord create(String goal, FilePermissionPolicy permission, EphemeralCredential credential) {
        return create(goal, permission, credential, null);
    }

    @Transactional
    public TaskRecord create(String goal, FilePermissionPolicy permission, EphemeralCredential credential,
            MailConnectionSettings mailCredential) {
        return create(goal, permission, credential, mailCredential, null);
    }

    /**
     * 迭代 18：带幂等键创建任务。
     *
     * <p>同一用户重复提交同一个键直接返回原任务，不重新调用 Planner；键与用户组成数据库唯一约束，
     * 单实例锁用于避免并发请求在 Planner 阶段重复产生副作用。</p>
     */
    @Transactional
    public TaskRecord create(String goal, FilePermissionPolicy permission, EphemeralCredential credential,
            MailConnectionSettings mailCredential, String rawIdempotencyKey) {
        return create(goal, permission, credential, mailCredential, rawIdempotencyKey, null);
    }

    /** 迭代 18C：固定评测任务必须引用稳定目录，且目标文本不得与目录漂移。 */
    @Transactional
    public TaskRecord create(String goal, FilePermissionPolicy permission, EphemeralCredential credential,
            MailConnectionSettings mailCredential, String rawIdempotencyKey, String rawBenchmarkCaseId) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("操作失败：任务目标不能为空。");
        }
        BenchmarkCase benchmarkCase = resolveBenchmarkCase(rawBenchmarkCaseId, goal);
        String benchmarkCaseId = benchmarkCase == null ? null : benchmarkCase.id();
        RequestIdentity identity = RequestIdentityContext.require();
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        if (idempotencyKey != null) {
            Semaphore lock = idempotencyLocks.computeIfAbsent(identity.userId(), ignored -> new Semaphore(1));
            lock.acquireUninterruptibly();
            boolean releaseHere = true;
            try {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            lock.release();
                        }
                    });
                    releaseHere = false;
                }
                TaskRecord existing = repository.findByUserIdAndIdempotencyKey(identity.userId(), idempotencyKey)
                        .orElse(null);
                if (existing != null) {
                    if (!existing.getGoal().equals(goal.trim())
                            || !java.util.Objects.equals(existing.getBenchmarkCaseId(), benchmarkCaseId)) {
                        throw new IllegalArgumentException("操作失败：幂等键已用于其他任务目标，请更换 Idempotency-Key。");
                    }
                    return existing;
                }
                return createNewTask(goal, permission, credential, mailCredential, idempotencyKey,
                        benchmarkCaseId, identity);
            } finally {
                if (releaseHere) {
                    lock.release();
                }
            }
        }
        return createNewTask(goal, permission, credential, mailCredential, null, benchmarkCaseId, identity);
    }

    private TaskRecord createNewTask(String goal, FilePermissionPolicy permission, EphemeralCredential credential,
            MailConnectionSettings mailCredential, String idempotencyKey, String benchmarkCaseId,
            RequestIdentity identity) {
        TaskRecord record = new TaskRecord(UUID.randomUUID().toString(), identity.userId(), identity.orgId(),
                goal.trim(), TaskStatus.PENDING, null, 0,
                permission == null ? null : toPermissionJson(permission));
        record.setIdempotencyKey(idempotencyKey);
        record.setBenchmarkCaseId(benchmarkCaseId);
        if (credential != null) {
            record.setModelSource("EPHEMERAL");
            ephemeralCredentials.put(record.getId(), credential);
            UsageContext.set(usageSnapshot(record, "PLANNER", null, "HIGH", contextBudget.plannerTokens(), false,
                    "planner", "Planner"));
            try {
                TaskPlan plan = plannerAgent.plan(goal.trim(),
                        registry.ephemeralClient(credential, false, com.example.vatica.config.ReasoningMode.HIGH),
                        credential.toSlot());
                bindSkills(plan, identity);
                record.setPlanJson(toJson(plan));
            } finally {
                UsageContext.clear();
            }
        } else {
            record.setModelSource("PLATFORM");
            record.setModelSlotId(null);
            UsageContext.set(usageSnapshot(record, "PLANNER", null, "HIGH", contextBudget.plannerTokens(), true,
                    "planner", "Planner"));
            try {
                TaskPlan plan = plannerAgent.plan(goal.trim());
                bindSkills(plan, identity);
                record.setPlanJson(toJson(plan));
            } finally {
                UsageContext.clear();
            }
        }
        if (mailCredential != null) {
            ephemeralMailCredentials.put(record.getId(), mailCredential);
        }
        return repository.save(record);
    }

    private BenchmarkCase resolveBenchmarkCase(String rawBenchmarkCaseId, String goal) {
        if (rawBenchmarkCaseId == null || rawBenchmarkCaseId.isBlank()) {
            return null;
        }
        BenchmarkCase benchmarkCase = benchmarkCatalog.find(rawBenchmarkCaseId)
                .orElseThrow(() -> new IllegalArgumentException("操作失败：评测用例不存在。"));
        if (!benchmarkCase.goal().equals(goal.trim())) {
            throw new IllegalArgumentException("操作失败：评测任务目标必须与固定任务集保持一致。\n"
                    + "请使用目录中的目标：" + benchmarkCase.goal());
        }
        return benchmarkCase;
    }

    /** Planner 只选角色；Vatica 在计划落库前固定当前组织的 Skill 版本。 */
    private void bindSkills(TaskPlan plan, RequestIdentity identity) {
        java.util.Set<String> roles = plan.getSteps().stream()
                .map(step -> agentRegistry.normalizeId(step.getAgent()))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, ExecutionProfile> profiles = skillCatalog.resolveForPlanning(identity, roles);
        for (TaskStep step : plan.getSteps()) {
            String role = agentRegistry.normalizeId(step.getAgent());
            step.setAgent(role);
            ExecutionProfile profile = profiles.get(role);
            if (profile == null) {
                step.setSkillId(null);
                step.setSkillVersion(null);
            } else {
                step.setSkillId(profile.id());
                step.setSkillVersion(profile.version());
            }
        }
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (key.length() > 128) {
            throw new IllegalArgumentException("操作失败：Idempotency-Key 不能超过 128 个字符。");
        }
        return key;
    }

    /** 查询单任务。 */
    public TaskRecord get(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        return repository.findByIdAndUserId(id, identity.userId())
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    /** 最近任务（前端任务列表用）。 */
    public List<TaskRecord> recent(int limit) {
        RequestIdentity identity = RequestIdentityContext.require();
        return repository.findByUserIdOrderByCreatedAtDesc(identity.userId(),
                PageRequest.of(0, Math.min(Math.max(limit, 1), 100)));
    }

    /** 迭代 17B：HumanAgent 写 note；与任务计划同事务落库并立即发布黑板事件。 */
    @Transactional
    public TaskRecord addHumanNote(String id, String content) {
        TaskRecord record = get(id);
        if (record.getStatus().isTerminal()) {
            throw new IllegalArgumentException("操作失败：终态任务不能再写协作备注。");
        }
        TaskPlan plan = parse(record.getPlanJson());
        int currentStepId = record.getCurrentStep() >= 0 && record.getCurrentStep() < plan.getSteps().size()
                ? plan.getSteps().get(record.getCurrentStep()).getId() : 0;
        BlackboardEntry entry = humanAgent.note(plan, RequestIdentityContext.require(), currentStepId, content);
        record.setPlanJson(toJson(plan));
        repository.save(record);
        eventPublisher.publishBlackboard(record, entry);
        eventPublisher.publish(record, "human_note");
        return record;
    }

    /**
     * 迭代 13 I13-6：启动清理器——EPHEMERAL 任务重启后凭据已丢，直接 FAILED 提示重提；
     * PLATFORM 任务中断标 FAILED + recoverable，可手动 continue。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedTasks() {
        for (TaskRecord record : repository.findAll()) {
            if (record.getStatus() == TaskStatus.DONE || record.getStatus() == TaskStatus.CANCELLED) {
                continue;
            }
            if ("EPHEMERAL".equals(record.getModelSource())) {
                ephemeralCredentials.remove(record.getId());
                ephemeralMailCredentials.remove(record.getId());
                record.setStatus(TaskStatus.FAILED);
                record.setRecoverable(false);
                record.setError("服务重启，临时模型凭据已失效，请重新提交任务。");
                repository.save(record);
                continue;
            }
            if (record.getStatus() == TaskStatus.RUNNING || record.getStatus() == TaskStatus.PENDING_APPROVAL
                    || record.getStatus() == TaskStatus.REVIEW || record.getStatus() == TaskStatus.RETRY) {
                record.setStatus(TaskStatus.FAILED);
                record.setRecoverable(true);
                record.setRecoveryApprovalRequired(false);
                record.setError("服务重启导致任务中断，已完成步骤会保留；点击继续执行后需确认中断步骤。");
                record.setLastHeartbeatAt(Instant.now());
                repository.save(record);
            }
        }
    }

    /** 迭代 18：重启中断任务的手动恢复，保留已完成步骤并把未知副作用边界交给人工确认。 */
    @Transactional
    public TaskRecord resume(String id) {
        synchronized (mutationLock(id)) {
            TaskRecord record = get(id);
            if (record.getStatus() != TaskStatus.FAILED || !record.isRecoverable()) {
                throw new IllegalArgumentException("操作失败：该任务不可继续执行，请重新提交。");
            }
            TaskPlan plan = parse(record.getPlanJson());
            int next = nextIncompleteIndex(plan);
            beginExecution(record);
            record.setRecoverable(false);
            record.setError(null);
            record.setLastFeedbackJson(null);
            record.setPlanRevisionCount(0);
            if (next < plan.getSteps().size()) {
                // 未知步骤可能已经完成了不可逆副作用，恢复不得静默重放。
                TaskStep interrupted = plan.getSteps().get(next);
                record.setStatus(TaskStatus.PENDING_APPROVAL);
                record.setPendingStepId(interrupted.getId());
                record.setCurrentStep(next);
                record.setRecoveryApprovalRequired(true);
                record.setError("服务重启后请确认从步骤 " + interrupted.getId() + " 继续；已完成步骤不会重跑。" );
                record.setPlanJson(toJson(plan));
                repository.save(record);
                eventPublisher.publish(record, "recovery_approval_required");
                return record;
            }
            record.setStatus(TaskStatus.RUNNING);
            record.setPendingStepId(-1);
            repository.save(record);
            eventPublisher.publish(record, "resumed");
            executeUntilBlocked(record);
            return repository.save(record);
        }
    }

    /**
     * 审批：PENDING=批准计划并开始执行；PENDING_APPROVAL=批准挂起步骤并继续执行。
     * 同步执行到下一个审批点或任务终态后返回。
     */
    @Transactional
    public TaskRecord approve(String id) {
        synchronized (mutationLock(id)) {
            return approveLocked(id);
        }
    }

    private TaskRecord approveLocked(String id) {
        TaskRecord record = get(id);
        TaskStatus from = record.getStatus();
        switch (from) {
            case PENDING -> {
                TaskStateMachine.requireTransition(from, TaskStatus.RUNNING);
                beginExecution(record);
                record.setStatus(TaskStatus.RUNNING);
                repository.save(record);
            }
            case PENDING_APPROVAL -> {
                TaskStateMachine.requireTransition(from, TaskStatus.RUNNING);
                TaskPlan plan = parse(record.getPlanJson());
                if (record.getPendingStepId() >= 0) {
                    TaskStep pending = findStep(plan, record.getPendingStepId());
                    pending.setApproved(true); // 批准后跳过该审批点
                } else {
                    if (blackboard.openArbitrations(plan).isEmpty()) {
                        throw new IllegalArgumentException("操作失败：当前没有待处理的协作仲裁。");
                    }
                    if (!blackboard.hasHumanNoteForOpenArbitration(plan)) {
                        throw new IllegalArgumentException("操作失败：请先写入人工判断说明，再批准继续。");
                    }
                    List<BlackboardEntry> resolved = blackboard.resolveOpenArbitrationsByHuman(plan);
                    resolved.forEach(entry -> eventPublisher.publishBlackboard(record, entry));
                    record.setError(null);
                }
                record.setPendingStepId(-1);
                record.setRecoveryApprovalRequired(false);
                record.setPlanJson(toJson(plan));
                record.setStatus(TaskStatus.RUNNING);
                repository.save(record);
            }
            default -> throw new IllegalArgumentException(
                    "操作失败：当前状态（" + from + "）不需要审批。");
        }
        executeUntilBlocked(record);
        // 执行期间被终止：状态已由 cancel() 事务提交为 CANCELLED。
        // 在事务隔离级别下普通 refresh 可能读到事务快照，
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
                permissionRequests.cancelChannel(TenantChannels.task(identityOf(record), id));
                ephemeralCredentials.remove(id);
                ephemeralMailCredentials.remove(id);
                record.setLastHeartbeatAt(Instant.now());
                record.setExecutionFinishedAt(Instant.now());
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
        synchronized (mutationLock(id)) {
            return reworkLocked(id);
        }
    }

    private TaskRecord reworkLocked(String id) {
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
        record.setLastFeedbackJson(null);   // 迭代 15：人工返工开启新一轮反思链
        record.setPlanRevisionCount(0);
        record.setScore(null);
        record.setVerdict(null);
        record.setError(null);
        record.setRecoveryApprovalRequired(false);
        beginExecution(record);
        repository.save(record);
        executeUntilBlocked(record);
        // 迭代 10 I10-6：与 approve 同款的取消兜底——执行期间若取消标志已置位，
        // 用悲观锁当前读刷新到库中已提交状态，避免本事务把 RUNNING/其他中间态覆盖回库
        if (isCancelled(record.getId())) {
            entityManager.refresh(record, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        }
        return repository.save(record);
    }

    /** 从 currentStep 波次执行（迭代 6 并行）：遇审批点挂起；全部完成 → REVIEW 评测段；异常 → FAILED。 */
    private void executeUntilBlocked(TaskRecord record) {
        TaskPlan plan = parse(record.getPlanJson());
        boolean restartScheduling;
        do {
            restartScheduling = false;
            List<List<Integer>> waves = WaveScheduler.waves(plan);
            for (List<Integer> wave : waves) {
                // 迭代 17B：以持久化 result 判断是否完成；重规划重算波次时绝不重复执行已完成步骤。
                List<Integer> todo = wave.stream()
                        .filter(index -> !TaskBlackboard.hasResult(plan.getSteps().get(index)))
                        .toList();
                if (todo.isEmpty()) {
                    continue;
                }
                if (isCancelled(record.getId())) {
                    return;
                }
                record.setCurrentStep(todo.stream().min(Integer::compareTo).orElse(0));
                heartbeat(record);

                // 显式共享写资源冲突在任何工具调用之前机械阻断。
                List<BlackboardEntry> conflicts = blackboard.detectWriteConflicts(plan, todo);
                if (!conflicts.isEmpty()) {
                    record.setPlanJson(toJson(plan));
                    repository.save(record);
                    conflicts.forEach(entry -> eventPublisher.publishBlackboard(record, entry));
                    eventPublisher.publish(record, "conflict_detected");
                    CollaborationRoute route = handleCollaboration(record, plan, conflicts);
                    if (route == CollaborationRoute.HUMAN) {
                        return;
                    }
                    if (route == CollaborationRoute.RESTART) {
                        restartScheduling = true;
                        break;
                    }
                }

                // 审批点屏障：未批准的审批步骤独占一波（discovery 动态补步同样适用）。
                if (todo.size() == 1) {
                    int idx = todo.getFirst();
                    TaskStep step = plan.getSteps().get(idx);
                    if (step.isNeedsApproval() && !step.isApproved()) {
                        TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.PENDING_APPROVAL);
                        record.setStatus(TaskStatus.PENDING_APPROVAL);
                        record.setPendingStepId(step.getId());
                        record.setCurrentStep(idx);
                        heartbeat(record);
                        record.setPlanJson(toJson(plan));
                        repository.save(record);
                        eventPublisher.publish(record, "approval_required");
                        log.info("任务 {} 步骤 {} 命中审批点，挂起等待人工审批", record.getId(), step.getId());
                        return;
                    }
                }

                String reflection = reflectionPrompt(record);
                List<CompletableFuture<String>> futures = new ArrayList<>();
                try {
                    eventPublisher.publish(record, "step_running");
                    for (int idx : todo) {
                        TaskStep step = plan.getSteps().get(idx);
                        List<String> context = blackboard.contextFor(record.getGoal(), plan, step);
                        futures.add(CompletableFuture
                                .supplyAsync(() -> execute(record, step, context, reflection), parallelExecutor)
                                .orTimeout(reliability.stepTimeout().toMillis(), TimeUnit.MILLISECONDS));
                    }
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
                    if (isCancelled(record.getId())) {
                        futures.forEach(future -> future.cancel(true));
                        return;
                    }

                    List<BlackboardEntry> waveEntries = new ArrayList<>();
                    List<BlackboardEntry> helpSignals = new ArrayList<>();
                    boolean discoveryAdded = false;
                    UsageContext.set(usageSnapshot(record, "SUMMARIZER", null, "DISABLED",
                            contextBudget.summarizerTokens(), true, "summarizer", "Summarizer"));
                    try {
                        for (int k = 0; k < todo.size(); k++) {
                            TaskStep step = plan.getSteps().get(todo.get(k));
                            TaskBlackboard.ProcessedOutcome outcome = blackboard.recordStepOutput(
                                    plan, step, futures.get(k).join());
                            waveEntries.addAll(outcome.entries());
                            if (outcome.needHelp() != null) {
                                helpSignals.add(outcome.needHelp());
                            }
                            TaskBlackboard.DiscoveryResult discovery = blackboard.appendDiscoveries(
                                    plan, outcome.discoveries(), step.getId(), agentRegistry);
                            discoveryAdded |= discovery.addedCount() > 0;
                            waveEntries.addAll(discovery.entries());
                        }
                        blackboard.mergeWaveNotes(plan);
                    } finally {
                        UsageContext.clear();
                    }
                    record.setPlanJson(toJson(plan));
                    record.setCurrentStep(nextIncompleteIndex(plan));
                    heartbeat(record);
                    repository.save(record);
                    waveEntries.forEach(entry -> eventPublisher.publishBlackboard(record, entry));
                    eventPublisher.publish(record, helpSignals.isEmpty() ? "step_done" : "need_help");
                    log.info("任务 {} 波次完成：步骤下标 {}（{} 个并行）", record.getId(), todo, todo.size());

                    if (!helpSignals.isEmpty()) {
                        CollaborationRoute route = handleCollaboration(record, plan, helpSignals);
                        if (route == CollaborationRoute.HUMAN) {
                            return;
                        }
                        if (route == CollaborationRoute.RESTART) {
                            restartScheduling = true;
                            break;
                        }
                    }
                    if (discoveryAdded) {
                        restartScheduling = true;
                        break;
                    }
                } catch (Exception e) {
                    futures.forEach(future -> future.cancel(true));
                    if (isCancelled(record.getId())) {
                        return;
                    }
                    Throwable cause = unwrapCompletionException(e);
                    if (cause instanceof TimeoutException) {
                        cause = new IllegalStateException("步骤执行超时（超过 "
                                + formatDuration(reliability.stepTimeout()) + "）。", cause);
                    }
                    log.error("任务 {} 波次执行失败（步骤 {}）", record.getId(), todo, cause);
                    TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.FAILED);
                    record.setStatus(TaskStatus.FAILED);
                    record.setError(cause.getMessage());
                    markExecutionFinished(record);
                    cancelFlags.remove(record.getId());
                    ephemeralCredentials.remove(record.getId());
                    ephemeralMailCredentials.remove(record.getId());
                    repository.save(record);
                    eventPublisher.publish(record, "failed");
                    return;
                }
            }
        } while (restartScheduling);

        // 全部完成：进入 REVIEW 评测段（迭代 5.5：Judge 评分分流）
        if (isCancelled(record.getId())) {
            return;
        }
        TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.REVIEW);
        record.setStatus(TaskStatus.REVIEW);
        record.setCurrentStep(-1);
        record.setPendingStepId(-1);
        heartbeat(record);
        repository.save(record);
        eventPublisher.publish(record, "review");
        evaluateAndRoute(record, plan);
    }

    /** 运行中协作只有一次 Planner 调整预算；未决冲突/首次未决求助进入 HumanAgent。 */
    private CollaborationRoute handleCollaboration(TaskRecord record, TaskPlan plan,
            List<BlackboardEntry> signals) {
        boolean onlyNeedHelp = signals.stream().allMatch(entry -> BlackboardEntry.NEED_HELP.equals(entry.type()));
        if (plan.getCollaborationRevisionCount() >= 1) {
            if (onlyNeedHelp) {
                List<BlackboardEntry> exhausted = blackboard.exhaustNeedHelp(plan, signals);
                record.setPlanJson(toJson(plan));
                record.setCurrentStep(nextIncompleteIndex(plan));
                repository.save(record);
                exhausted.forEach(entry -> eventPublisher.publishBlackboard(record, entry));
                eventPublisher.publish(record, "collaboration_budget_exhausted");
                return CollaborationRoute.CONTINUE;
            }
            pauseForHuman(record, plan, "共享资源冲突已超过自动裁决预算，请写入人工判断说明后继续。");
            return CollaborationRoute.HUMAN;
        }

        plan.setCollaborationRevisionCount(plan.getCollaborationRevisionCount() + 1);
        CollaborationDecision decision;
        boolean platformQuota = !"EPHEMERAL".equals(record.getModelSource());
        UsageContext.set(usageSnapshot(record, "PLANNER_COLLABORATION", null, "HIGH",
                contextBudget.plannerTokens(), platformQuota, "planner", "Planner"));
        try {
            int remainingDiscoveries = Math.max(0,
                    TaskBlackboard.MAX_DISCOVERY_STEPS - plan.getDiscoveryStepCount());
            decision = RequestIdentityContext.callWith(identityOf(record), () -> {
                ChatClient client = plannerClientFor(record);
                if ("EPHEMERAL".equals(record.getModelSource())) {
                    return plannerAgent.resolveCollaboration(record.getGoal(), plan, signals,
                            remainingDiscoveries, client, ephemeralCredentialFor(record).toSlot());
                }
                return plannerAgent.resolveCollaboration(record.getGoal(), plan, signals,
                        remainingDiscoveries, client);
            });
        } catch (RuntimeException e) {
            log.warn("任务 {} 协作 Planner 裁决失败，升级人工：{}", record.getId(), e.getMessage());
            decision = CollaborationDecision.unresolved("Planner 裁决失败：" + e.getMessage());
        } finally {
            UsageContext.clear();
        }

        TaskBlackboard.ApplyResult applied = blackboard.applyDecision(plan, decision, signals, agentRegistry);
        if (applied.changed()) {
            record.setPlanJson(toJson(plan));
            record.setCurrentStep(nextIncompleteIndex(plan));
            repository.save(record);
            applied.entries().forEach(entry -> eventPublisher.publishBlackboard(record, entry));
            eventPublisher.publish(record, "collaboration_replanned");
            return CollaborationRoute.RESTART;
        }

        String reason = decision == null || decision.summary() == null || decision.summary().isBlank()
                ? "Planner 无法可靠裁决，请写入人工判断说明后继续。" : decision.summary();
        pauseForHuman(record, plan, reason);
        return CollaborationRoute.HUMAN;
    }

    private void pauseForHuman(TaskRecord record, TaskPlan plan, String reason) {
        TaskStateMachine.requireTransition(record.getStatus(), TaskStatus.PENDING_APPROVAL);
        record.setStatus(TaskStatus.PENDING_APPROVAL);
        record.setPendingStepId(-1);
        record.setError("等待人工仲裁：" + reason);
        record.setPlanJson(toJson(plan));
        repository.save(record);
        eventPublisher.publish(record, "arbitration_required");
    }

    private static int nextIncompleteIndex(TaskPlan plan) {
        for (int i = 0; i < plan.getSteps().size(); i++) {
            if (!TaskBlackboard.hasResult(plan.getSteps().get(i))) {
                return i;
            }
        }
        return plan.getSteps().size();
    }

    private enum CollaborationRoute {
        CONTINUE,
        RESTART,
        HUMAN
    }

    /** 单步骤执行包装：异常附步骤号（并行波中定位失败来源）；supplyAsync 会再包一层 CompletionException。 */
    private String execute(TaskRecord record, TaskStep step, List<String> context, String reflection) {
        try {
            faultInjector.beforeStep(record, step);
            // 迭代 11：把任务创建时的权限快照绑定到本次步骤的全部工具调用
            FilePermissionPolicy policy = parsePermission(record.getPermissionJson());
            RequestIdentity identity = identityOf(record);
            ToolCallback[] callbacks = PermissionBoundToolCallbacks.wrap(
                    agentTools::callbacks, policy, TenantChannels.task(identity, record.getId()), identity,
                    ephemeralMailCredentials.get(record.getId()));
            // 迭代 15 I15-3：retryable 工具错误重试 1 次（权限最内层，重试也重新校验身份/权限）
            callbacks = new RetryableToolCallbacks().wrap(callbacks);
            // 迭代 17A：角色工具白名单是机械门禁，位于 prompt 之外，模型无法请求未注册工具。
            var agent = agentRegistry.resolve(step.getAgent());
            step.setAgent(agent.id());
            callbacks = agentRegistry.allowedCallbacks(agent.id(), callbacks);
            // 迭代 20B：按组织复核启用状态，并解析任务固定的不可变 Skill 版本。
            ExecutionProfile skill = skillCatalog.resolveForExecution(identity, agent.id(),
                    step.getSkillId(), step.getSkillVersion()).orElse(null);
            if (skill != null) {
                step.setSkillId(skill.id());
                step.setSkillVersion(skill.version());
            }
            // 迭代 17C：绑定链同时决定执行模型和观测维度；临时凭据只在本任务内优先。
            AgentModelBindingService.Resolution model = agentModelBindings.resolve(identity, agent.id(),
                    agent.modelCapability(), ephemeralCredentials.get(record.getId()));
            // 迭代 15 I15-1：权限包装在内层，trace 在最外层看到真实耗时与最终结果
            TraceContext.Snapshot trace = new TraceContext.Snapshot(UUID.randomUUID().toString(),
                    TenantChannels.task(identity, record.getId()), record.getId(), step.getId(),
                    identity.userId(), identity.orgId(), true, agent.id(), agent.displayName());
            callbacks = new TracedToolCallbacks(mapper, traceRepository).wrap(callbacks, trace);
            boolean platformQuota = !"EPHEMERAL".equals(record.getModelSource());
            UsageContext.set(usageSnapshot(record, "EXECUTOR", step.getId(), "LOW",
                    contextBudget.executorTokens(), platformQuota, agent.id(), agent.displayName(), model.slot().id()));
            if (model.fallback()) {
                directUsage.recordFallback(model.fallbackReason(), model.slot().id());
            }
            String result;
            try {
                AgentRuntime runtime = runtimeFactory.runtime();
                AgentRuntime.StepRequest request = new AgentRuntime.StepRequest(
                        record.getGoal(), step, context, reflection, identity, callbacks,
                        clientFor(record, true, model.slot()), model.slot(), agent,
                        record.getId() + ":step:" + step.getId(), skill);
                if (AgentRuntimeProperties.AGENTSCOPE.equals(runtime.name())) {
                    DirectModelUsageRecorder.Reservation reservation = directUsage.begin();
                    try {
                        AgentRuntime.StepResult stepResult = runtime.executeStep(request);
                        directUsage.complete(reservation, stepResult.usage(), stepResult.durationMs());
                        result = stepResult.answer();
                    } catch (RuntimeException | Error e) {
                        directUsage.abort(reservation);
                        // 迭代 17C：仅对明确的认证/超时类模型故障做一次角色级槽位恢复，避免副作用工具重复执行。
                        if (!(e instanceof Error) && !"EPHEMERAL".equals(record.getModelSource())
                                && isRecoverableModelError(e)) {
                            AgentModelBindingService.Resolution recovery = agentModelBindings.recover(identity,
                                    agent.id(), agent.modelCapability(), model.slot().id());
                            directUsage.recordFallback(recovery.fallbackReason(), recovery.slot().id());
                            UsageContext.set(usageSnapshot(record, "EXECUTOR", step.getId(), "LOW",
                                    contextBudget.executorTokens(), platformQuota, agent.id(), agent.displayName(),
                                    recovery.slot().id()));
                            AgentRuntime.StepRequest retry = new AgentRuntime.StepRequest(
                                    record.getGoal(), step, context, reflection, identity, callbacks,
                                    clientFor(record, true, recovery.slot()), recovery.slot(), agent,
                                    record.getId() + ":step:" + step.getId() + ":recovery", skill);
                            DirectModelUsageRecorder.Reservation retryReservation = directUsage.begin();
                            try {
                                AgentRuntime.StepResult stepResult = runtime.executeStep(retry);
                                directUsage.complete(retryReservation, stepResult.usage(), stepResult.durationMs());
                                result = stepResult.answer();
                            } catch (RuntimeException | Error retryError) {
                                directUsage.abort(retryReservation);
                                throw retryError;
                            }
                        } else {
                            throw e;
                        }
                    }
                } else {
                    result = runtime.executeStep(request).answer();
                }
            } finally {
                UsageContext.clear();
            }
            // 迭代 15 I15-7：执行器思考摘要也进 agent_trace（不存全文，与工具 trace 同源可查询）
            persistThinkingTrace(record, step, identity, trace, ReasoningContext.take());
            return result;
        } catch (RuntimeException e) {
            throw new IllegalStateException("步骤 " + step.getId() + " 执行失败：" + e.getMessage(), e);
        }
    }

    /** 迭代 13 I13-5：按任务模型来源解析客户端；临时凭据仅内存，缺失即失败。
     *  迭代 15 I15-4：平台槽位走 executorClient（LOW）/judgeClient（HIGH）。 */
    private ChatClient clientFor(TaskRecord record, boolean withTools) {
        return clientFor(record, withTools, null);
    }

    private static boolean isRecoverableModelError(Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("401") || message.contains("unauthorized")
                || message.contains("timeout") || message.contains("timed out");
    }

    private ChatClient clientFor(TaskRecord record, boolean withTools, ModelSlot selectedSlot) {
        if ("EPHEMERAL".equals(record.getModelSource())) {
            EphemeralCredential credential = ephemeralCredentialFor(record);
            return registry.ephemeralClient(credential, false,
                    withTools ? com.example.vatica.config.ReasoningMode.LOW
                            : com.example.vatica.config.ReasoningMode.HIGH);
        }
        if (withTools) {
            return selectedSlot == null ? registry.taskExecutorClient() : registry.taskClientFor(selectedSlot);
        }
        return registry.judgeClient();
    }

    private ChatClient plannerClientFor(TaskRecord record) {
        if ("EPHEMERAL".equals(record.getModelSource())) {
            EphemeralCredential credential = ephemeralCredentialFor(record);
            return registry.ephemeralClient(credential, false,
                    com.example.vatica.config.ReasoningMode.HIGH);
        }
        return registry.plannerClient();
    }

    private EphemeralCredential ephemeralCredentialFor(TaskRecord record) {
        EphemeralCredential credential = ephemeralCredentials.get(record.getId());
        if (credential == null) {
            throw new IllegalStateException("操作失败：本任务的临时模型凭据已失效（服务重启），请重新提交任务。");
        }
        return credential;
    }

    /** REVIEW 段：Judge 评分 → PASS 交付 DONE；FAIL 自动返工（限次）或超限 NEEDS_REVISION；评测异常 FAILED。 */
    private void evaluateAndRoute(TaskRecord record, TaskPlan plan) {
        if (isCancelled(record.getId())) {
            return;   // 评测前/中被终止：状态保持 CANCELLED
        }
        JudgeAgent.Evaluation eval;
        try {
            UsageContext.set(usageSnapshot(record, "JUDGE", null, "HIGH", contextBudget.judgeTokens(),
                    !"EPHEMERAL".equals(record.getModelSource()), "judge", "Judge"));
            try {
                TaskPlan evaluationPlan = plan;
                eval = RequestIdentityContext.callWith(identityOf(record), () -> {
                    ChatClient client = clientFor(record, false);
                    if ("EPHEMERAL".equals(record.getModelSource())) {
                        return judgeAgent.evaluate(evaluationGoal(record), evaluationPlan, client,
                                ephemeralCredentialFor(record).toSlot());
                    }
                    return judgeAgent.evaluate(evaluationGoal(record), evaluationPlan, client);
                });
            } finally {
                UsageContext.clear();
            }
        } catch (Exception e) {
            log.error("任务 {} 评测异常", record.getId(), e);
            TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.FAILED);
            record.setStatus(TaskStatus.FAILED);
            record.setError("评测异常：" + e.getMessage());
            markExecutionFinished(record);
            cancelFlags.remove(record.getId());
            ephemeralCredentials.remove(record.getId());
            ephemeralMailCredentials.remove(record.getId());
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
            markExecutionFinished(record);
            cancelFlags.remove(record.getId());
            ephemeralCredentials.remove(record.getId());
            ephemeralMailCredentials.remove(record.getId());
            log.info("任务 {} 评测通过（{} 分），交付", record.getId(), eval.score());
            repository.save(record);
            eventPublisher.publish(record, "done");
        } else if (record.getReworkCount() >= judgeProps.maxAutoRework()) {
            TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.NEEDS_REVISION);
            record.setStatus(TaskStatus.NEEDS_REVISION);
            record.setError("评测不合格（" + eval.score() + " 分）：" + eval.summary()
                    + "。自动返工已达上限 " + judgeProps.maxAutoRework() + " 次，请人工返工。");
            markExecutionFinished(record);
            cancelFlags.remove(record.getId());
            log.info("任务 {} 评测不合格且自动返工超限，转人工", record.getId());
            repository.save(record);
            eventPublisher.publish(record, "needs_revision");
        } else {
            TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.RETRY);
            record.setStatus(TaskStatus.RETRY);
            record.setReworkCount(record.getReworkCount() + 1);
            // 迭代 15 I15-2：持久化 Judge 反馈（含失败步骤），反馈链随返工轮次累积
            ReflectionFeedback feedback = latestFeedback(record, eval);
            record.setLastFeedbackJson(toJsonObject(feedback));
            if (record.getPlanRevisionCount() < 1) {
                // 第 1 次返工：让 Planner 针对失败步骤重规划（限 1 次）；解析失败回退旧计划
                TaskPlan previousPlan = plan;
                TaskPlan revised = RequestIdentityContext.callWith(identityOf(record), () -> {
                    if ("EPHEMERAL".equals(record.getModelSource())) {
                        return plannerAgent.revise(record.getGoal(), previousPlan, feedback,
                                plannerClientFor(record), ephemeralCredentialFor(record).toSlot());
                    }
                    return plannerAgent.revise(record.getGoal(), previousPlan, feedback);
                });
                record.setPlanRevisionCount(record.getPlanRevisionCount() + 1);
                if (revised != plan) {
                    bindSkills(revised, identityOf(record));
                    revised.setCollaborationRevisionCount(plan.getCollaborationRevisionCount());
                    revised.setDiscoveryStepCount(plan.getDiscoveryStepCount());
                    plan = revised;
                    record.setPlanJson(toJson(plan));
                    record.setCurrentStep(0);
                    record.setPendingStepId(-1);
                } else {
                    resetPlanForRerun(record, plan);
                }
            } else {
                // 第 2 次及以后：不再重规划，只按反馈重跑（防目标漂移与无限改计划）
                resetPlanForRerun(record, plan);
            }
            repository.save(record);
            eventPublisher.publish(record, "retry");
            TaskStateMachine.requireTransition(TaskStatus.RETRY, TaskStatus.RUNNING);
            record.setStatus(TaskStatus.RUNNING);
            log.info("任务 {} 评测不合格（{} 分），第 {} 次自动返工（重规划 {} 次）", record.getId(),
                    eval.score(), record.getReworkCount(), record.getPlanRevisionCount());
            executeUntilBlocked(record);   // 递归重跑，深度由 max-auto-rework 兜底
        }
    }

    /** 构建最新反馈：把上一轮的 summary 并入 history，形成多轮反思链。 */
    private ReflectionFeedback latestFeedback(TaskRecord record, JudgeAgent.Evaluation eval) {
        List<String> history = new ArrayList<>();
        if (record.getLastFeedbackJson() != null && !record.getLastFeedbackJson().isBlank()) {
            try {
                ReflectionFeedback previous = mapper.readValue(record.getLastFeedbackJson(), ReflectionFeedback.class);
                history.addAll(previous.history());
                if (previous.summary() != null && !previous.summary().isBlank()) {
                    history.add(previous.summary());
                }
            } catch (Exception e) {
                // 旧数据损坏时丢弃历史，不影响本轮反馈
                log.warn("任务 {} 的历史反馈不可解析，重新开始反馈链", record.getId());
            }
        }
        return new ReflectionFeedback(eval.score(), eval.summary(), eval.failStepIds(), history);
    }

    /** 迭代 15 I15-13：任务各调用点用量上下文。 */
    private UsageContext.Snapshot usageSnapshot(TaskRecord record, String requestType, Integer stepId,
            String reasoningMode, int budgetTokens, boolean platformQuota) {
        return new UsageContext.Snapshot(UsageContext.newRequestId(), requestType, record.getUserId(),
                record.getOrgId(), record.getModelSlotId(), record.getId(), stepId, reasoningMode,
                budgetTokens, null, platformQuota);
    }

    /** 固定评测任务把验收标准显式交给 Judge；普通任务保持原有目标。 */
    private String evaluationGoal(TaskRecord record) {
        if (record.getBenchmarkCaseId() == null) {
            return record.getGoal();
        }
        return benchmarkCatalog.find(record.getBenchmarkCaseId())
                .map(item -> record.getGoal() + "\n固定评测验收条件：" + item.acceptance())
                .orElse(record.getGoal());
    }

    private UsageContext.Snapshot usageSnapshot(TaskRecord record, String requestType, Integer stepId,
            String reasoningMode, int budgetTokens, boolean platformQuota, String agentId, String role) {
        return usageSnapshot(record, requestType, stepId, reasoningMode, budgetTokens, platformQuota, agentId, role,
                record.getModelSlotId());
    }

    private UsageContext.Snapshot usageSnapshot(TaskRecord record, String requestType, Integer stepId,
            String reasoningMode, int budgetTokens, boolean platformQuota, String agentId, String role,
            String slotId) {
        return new UsageContext.Snapshot(UsageContext.newRequestId(), requestType, record.getUserId(),
                record.getOrgId(), slotId, record.getId(), stepId, reasoningMode,
                budgetTokens, null, platformQuota, agentId, role);
    }

    /** 迭代 15 I15-7：执行器思考摘要落 agent_trace（toolName=executor.thinking，全文不落库）。 */
    private void persistThinkingTrace(TaskRecord record, TaskStep step, RequestIdentity identity,
            TraceContext.Snapshot trace, String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return;
        }
        try {
            int max = 2000;
            String summary = reasoning.length() <= max ? reasoning : reasoning.substring(0, max) + "…（思考已截断）";
            traceRepository.save(new AgentTraceRecord(UUID.randomUUID().toString(),
                    identity.userId(), identity.orgId(), record.getId(), step.getId(), trace.traceId(),
                    trace.agentId(), trace.role(), "executor.thinking", "执行步骤 " + step.getId(), summary,
                    reasoning.length(), 0,
                    AgentTraceRecord.STATUS_SUCCESS, null));
        } catch (Exception e) {
            log.warn("思考摘要写入 agent_trace 失败：task={} step={}", record.getId(), step.getId(), e);
        }
    }

    /** Executor 注入用的人类可读反馈：包含本轮 summary 原文 + 历史反馈链。 */
    private String reflectionPrompt(TaskRecord record) {        if (record.getLastFeedbackJson() == null || record.getLastFeedbackJson().isBlank()) {
            return null;
        }
        try {
            ReflectionFeedback feedback = mapper.readValue(record.getLastFeedbackJson(), ReflectionFeedback.class);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < feedback.history().size(); i++) {
                sb.append("第 ").append(i + 1).append(" 轮：").append(feedback.history().get(i)).append('\n');
            }
            sb.append("本轮：").append(feedback.summary());
            if (!feedback.failStepIds().isEmpty()) {
                sb.append("（失败步骤：").append(feedback.failStepIds()).append("）");
            }
            return sb.toString();
        } catch (Exception e) {
            return "上一轮质量评测反馈：" + record.getLastFeedbackJson();
        }
    }

    /** 迭代 18：开始一次新的执行 run；运行时名称随任务落库，供 Legacy/AgentScope 对照。 */
    private void beginExecution(TaskRecord record) {
        record.setExecutionRunId(UUID.randomUUID().toString());
        record.setExecutionAttempt(record.getExecutionAttempt() + 1);
        record.setExecutionRuntime(runtimeFactory.runtime().name());
        Instant now = Instant.now();
        record.setExecutionStartedAt(now);
        record.setLastHeartbeatAt(now);
        record.setExecutionFinishedAt(null);
        record.setRecoverable(false);
    }

    /** 迭代 18：每个波次边界写入心跳，恢复时可区分正常等待审批与进程中断。 */
    private void heartbeat(TaskRecord record) {
        record.setLastHeartbeatAt(Instant.now());
    }

    private void markExecutionFinished(TaskRecord record) {
        Instant now = Instant.now();
        record.setLastHeartbeatAt(now);
        record.setExecutionFinishedAt(now);
    }

    private Object mutationLock(String taskId) {
        return mutationLocks.computeIfAbsent(taskId, ignored -> new Object());
    }

    private static Throwable unwrapCompletionException(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds % 60 == 0) {
            return (seconds / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    /** 是否已被用户终止（取消接口与执行线程的协作式协调点）。 */
    private boolean isCancelled(String taskId) {
        AtomicBoolean flag = cancelFlags.get(taskId);
        return flag != null && flag.get();
    }

    /** 返工重跑前重置计划：清步骤结果/摘要、撤审批标记、清黑板笔记（副作用步骤需重新审批=可重入设计）。 */
    private void resetPlanForRerun(TaskRecord record, TaskPlan plan) {
        for (TaskStep step : plan.getSteps()) {
            step.setResult(null);
            step.setResultDigest(null);
            step.setApproved(false);
        }
        plan.setGlobalNotes(null);
        plan.setNoteThroughStepId(0);
        plan.setBlackboard(List.of());
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

    private String toJsonObject(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：数据序列化失败。" + e.getMessage(), e);
        }
    }

    private FilePermissionPolicy parsePermission(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, FilePermissionPolicy.class);
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：任务文件权限数据损坏。" + e.getMessage(), e);
        }
    }

    private String toPermissionJson(FilePermissionPolicy policy) {
        try {
            return mapper.writeValueAsString(policy);
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：文件权限序列化失败。" + e.getMessage(), e);
        }
    }

    private static TaskStep findStep(TaskPlan plan, int stepId) {
        return plan.getSteps().stream()
                .filter(s -> s.getId() == stepId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：计划中找不到步骤 id=" + stepId));
    }

    private static RequestIdentity identityOf(TaskRecord record) {
        if (record.getUserId() == null || record.getOrgId() == null) {
            throw new IllegalStateException("操作失败：任务缺少租户归属，旧任务不允许在云模式继续执行。");
        }
        return new RequestIdentity(record.getUserId(), record.getOrgId(), "TASK_OWNER", "task-owner");
    }
}
