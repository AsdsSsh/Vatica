package com.example.vatica.task;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Index;

/**
 * 任务持久化实体（迭代 5 I5-4）：计划与步骤结果以 JSON 存 TEXT 列
 * （步骤列表是文档型数据，没必要为每个步骤建表）；状态用枚举字符串列。
 *
 * <p>score / reworkCount / verdict 为迭代 5.5 质量闭环字段（Judge 评分、自动返工限次、评测结论）。
 */
@Entity
@Table(name = "vatica_task", indexes = {
        @Index(name = "idx_task_owner_created", columnList = "userId,createdAt"),
        @Index(name = "uk_task_owner_idempotency", columnList = "userId,idempotencyKey", unique = true) })
public class TaskRecord {

    @Id
    @Column(length = 36)
    private String id;

    /** 迭代 14：创建者与组织快照，所有用户侧读写均按 userId 收口。 */
    @Column(updatable = false)
    private Long userId;

    @Column(updatable = false)
    private Long orgId;

    /** 迭代 18：同一用户的创建请求幂等键；空值表示调用方未启用幂等语义。 */
    @Column(length = 128)
    private String idempotencyKey;

    /** 用户原始目标（一句话）。 */
    @Column(nullable = false, length = 4000)
    private String goal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status;

    /** 计划 JSON（含各步骤结果字段，随执行更新）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String planJson;

    /** 迭代 11：创建任务时的文件权限快照（JSON），执行期据此校验工作区。 */
    @Column(columnDefinition = "TEXT")
    private String permissionJson;

    /** 迭代 13：模型来源 PLATFORM（平台槽位）| EPHEMERAL（请求级临时凭据，不落库）。 */
    @Column(nullable = false, length = 16)
    private String modelSource = "PLATFORM";

    /** 迭代 13：PLATFORM 来源时使用的槽位 id（EPHEMERAL 为 null）。 */
    @Column(length = 64)
    private String modelSlotId;

    /** 迭代 13：服务重启中断后是否可"继续执行"（平台/加密保存凭据的任务为 true）。 */
    @Column(nullable = false)
    private boolean recoverable;

    /** 当前待执行步骤下标（0 起）；全部完成置 -1。 */
    @Column(nullable = false)
    private int currentStep;

    /** 挂起待审批的步骤 id；无挂起置 -1。 */
    @Column(nullable = false)
    private int pendingStepId = -1;

    /** FAILED 原因（给用户/前端展示）。 */
    @Column(columnDefinition = "TEXT")
    private String error;

    /** 迭代 5.5 预留：Judge 评分（0-100）。 */
    private Integer score;

    /** 迭代 5.5 预留：自动返工次数（限 2）。 */
    @Column(nullable = false)
    private int reworkCount = 0;

    /** 迭代 5.5：最近一次评测结论（PASS/FAIL）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private TaskVerdict verdict;

    /** 迭代 15 I15-2：最近一次 Judge 反馈（含失败步骤与历史评语，JSON）。 */
    @Column(columnDefinition = "TEXT")
    private String lastFeedbackJson;

    /** 迭代 15 I15-2：自动返工期间 Planner 已重规划次数（上限 1）。 */
    @Column(nullable = false)
    private int planRevisionCount = 0;

    /** 迭代 18：乐观锁版本，阻止审批/取消/恢复并发回写旧任务状态。 */
    @Version
    private long version;

    /** 迭代 18：本次执行的稳定标识，重启恢复会生成新的 run。 */
    @Column(length = 36)
    private String executionRunId;

    /** 迭代 18：执行尝试次数（首次执行为 1，恢复/人工返工递增）。 */
    @Column(nullable = false)
    private int executionAttempt;

    /** 迭代 18：实际运行时名称，用于 Legacy/AgentScope 质量对照。 */
    @Column(length = 16)
    private String executionRuntime;

    /** 迭代 18：最近一次执行开始/心跳/结束时间，只记元数据不记业务内容。 */
    private Instant executionStartedAt;

    private Instant lastHeartbeatAt;

    private Instant executionFinishedAt;

    /** 迭代 18：重启恢复后的中断步骤需人工确认，避免静默重放副作用。 */
    @Column(nullable = false)
    private boolean recoveryApprovalRequired;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TaskRecord() {
        // JPA
    }

    public TaskRecord(String id, String goal, TaskStatus status, String planJson, int currentStep,
            String permissionJson) {
        this(id, null, null, goal, status, planJson, currentStep, permissionJson);
    }

    public TaskRecord(String id, Long userId, Long orgId, String goal, TaskStatus status, String planJson,
            int currentStep, String permissionJson) {
        this.id = id;
        this.userId = userId;
        this.orgId = orgId;
        this.goal = goal;
        this.status = status;
        this.planJson = planJson;
        this.currentStep = currentStep;
        this.permissionJson = permissionJson;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getGoal() {
        return goal;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public String getPermissionJson() {
        return permissionJson;
    }

    public String getModelSource() {
        return modelSource;
    }

    public void setModelSource(String modelSource) {
        this.modelSource = modelSource;
    }

    public String getModelSlotId() {
        return modelSlotId;
    }

    public void setModelSlotId(String modelSlotId) {
        this.modelSlotId = modelSlotId;
    }

    public boolean isRecoverable() {
        return recoverable;
    }

    public void setRecoverable(boolean recoverable) {
        this.recoverable = recoverable;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
    }

    public int getPendingStepId() {
        return pendingStepId;
    }

    public void setPendingStepId(int pendingStepId) {
        this.pendingStepId = pendingStepId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public int getReworkCount() {
        return reworkCount;
    }

    public void setReworkCount(int reworkCount) {
        this.reworkCount = reworkCount;
    }

    public TaskVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(TaskVerdict verdict) {
        this.verdict = verdict;
    }

    public String getLastFeedbackJson() {
        return lastFeedbackJson;
    }

    public void setLastFeedbackJson(String lastFeedbackJson) {
        this.lastFeedbackJson = lastFeedbackJson;
    }

    public int getPlanRevisionCount() {
        return planRevisionCount;
    }

    public void setPlanRevisionCount(int planRevisionCount) {
        this.planRevisionCount = planRevisionCount;
    }

    public long getVersion() {
        return version;
    }

    public String getExecutionRunId() {
        return executionRunId;
    }

    public void setExecutionRunId(String executionRunId) {
        this.executionRunId = executionRunId;
    }

    public int getExecutionAttempt() {
        return executionAttempt;
    }

    public void setExecutionAttempt(int executionAttempt) {
        this.executionAttempt = executionAttempt;
    }

    public String getExecutionRuntime() {
        return executionRuntime;
    }

    public void setExecutionRuntime(String executionRuntime) {
        this.executionRuntime = executionRuntime;
    }

    public Instant getExecutionStartedAt() {
        return executionStartedAt;
    }

    public void setExecutionStartedAt(Instant executionStartedAt) {
        this.executionStartedAt = executionStartedAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getExecutionFinishedAt() {
        return executionFinishedAt;
    }

    public void setExecutionFinishedAt(Instant executionFinishedAt) {
        this.executionFinishedAt = executionFinishedAt;
    }

    public boolean isRecoveryApprovalRequired() {
        return recoveryApprovalRequired;
    }

    public void setRecoveryApprovalRequired(boolean recoveryApprovalRequired) {
        this.recoveryApprovalRequired = recoveryApprovalRequired;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
