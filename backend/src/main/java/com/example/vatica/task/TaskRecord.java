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

/**
 * 任务持久化实体（迭代 5 I5-4）：计划与步骤结果以 JSON 存 TEXT 列
 * （步骤列表是文档型数据，没必要为每个步骤建表）；状态用枚举字符串列。
 *
 * <p>score / reworkCount 为迭代 5.5 质量闭环预留字段（Judge 评分、返工限次）。
 */
@Entity
@Table(name = "vatica_task")
public class TaskRecord {

    @Id
    @Column(length = 36)
    private String id;

    /** 用户原始目标（一句话）。 */
    @Column(nullable = false, length = 4000)
    private String goal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TaskStatus status;

    /** 计划 JSON（含各步骤结果字段，随执行更新）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String planJson;

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

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TaskRecord() {
        // JPA
    }

    public TaskRecord(String id, String goal, TaskStatus status, String planJson, int currentStep) {
        this.id = id;
        this.goal = goal;
        this.status = status;
        this.planJson = planJson;
        this.currentStep = currentStep;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
