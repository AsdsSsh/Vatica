package com.example.vatica.action;

import java.time.Instant;

import com.example.vatica.auth.RequestIdentity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 迭代 25B：已批准副作用的用户隔离执行记录。 */
@Entity
@Table(name = "action_execution", uniqueConstraints = @UniqueConstraint(name = "uk_action_execution_owner_key",
        columnNames = { "userId", "idempotencyKey" }), indexes = {
                @Index(name = "idx_action_execution_owner_subject", columnList = "userId,subjectType,subjectId"),
                @Index(name = "idx_action_execution_owner_status", columnList = "userId,status,updatedAt") })
public class ActionExecutionRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, updatable = false, length = 64)
    private String subjectType;

    @Column(nullable = false, updatable = false, length = 128)
    private String subjectId;

    @Column(nullable = false, updatable = false, length = 100)
    private String actionId;

    @Column(nullable = false, updatable = false, length = 64)
    private String actionType;

    @Column(nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, length = 100)
    private String requiredPermission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ActionExecutionStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(length = 100)
    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant approvedAt;

    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ActionExecutionRecord() {
        // JPA
    }

    public ActionExecutionRecord(String id, RequestIdentity identity, ActionPlanView plan,
            ActionPlanView.ActionItemView action) {
        this.id = id;
        this.userId = identity.userId();
        this.orgId = identity.orgId();
        this.subjectType = plan.subjectType();
        this.subjectId = plan.subjectId();
        this.actionId = action.id();
        this.actionType = action.type();
        this.idempotencyKey = action.idempotencyKey();
        this.requiredPermission = action.requiredPermission();
        this.status = ActionExecutionStatus.APPROVED;
        this.approvedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void begin() {
        if (status != ActionExecutionStatus.APPROVED) {
            throw new IllegalStateException("操作失败：动作不处于可执行状态。");
        }
        status = ActionExecutionStatus.RUNNING;
        attemptCount++;
        startedAt = Instant.now();
        finishedAt = null;
        errorCode = null;
        errorMessage = null;
    }

    public void succeed(String nextResult) {
        if (status != ActionExecutionStatus.RUNNING) {
            throw new IllegalStateException("操作失败：只有执行中的动作可以标记完成。");
        }
        status = ActionExecutionStatus.SUCCEEDED;
        result = nextResult;
        finishedAt = Instant.now();
        errorCode = null;
        errorMessage = null;
    }

    public void fail(String nextErrorCode, String nextErrorMessage) {
        if (status != ActionExecutionStatus.RUNNING) {
            throw new IllegalStateException("操作失败：只有执行中的动作可以标记失败。");
        }
        status = ActionExecutionStatus.FAILED;
        errorCode = nextErrorCode;
        errorMessage = nextErrorMessage;
        finishedAt = Instant.now();
    }

    /** 已结束请求留下的 RUNNING 记录可恢复；活动请求由上层同一业务对象锁串行化。 */
    public void requeueForRecovery() {
        if (status != ActionExecutionStatus.FAILED && status != ActionExecutionStatus.RUNNING) {
            return;
        }
        status = ActionExecutionStatus.APPROVED;
        finishedAt = null;
    }

    /** 取消只影响尚未开始的动作，不伪造对已执行副作用的回滚。 */
    public boolean cancelIfNotStarted() {
        if (status != ActionExecutionStatus.APPROVED) {
            return false;
        }
        status = ActionExecutionStatus.CANCELLED;
        finishedAt = Instant.now();
        return true;
    }

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public String getSubjectType() { return subjectType; }
    public String getSubjectId() { return subjectId; }
    public String getActionId() { return actionId; }
    public String getActionType() { return actionType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequiredPermission() { return requiredPermission; }
    public ActionExecutionStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getResult() { return result; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
