package com.example.vatica.trace;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 工具调用 trace（迭代 15 I15-1）：任务执行时的脱敏摘要级留痕——
 * 只存工具名、脱敏输入摘要、输出头尾摘要、耗时与状态，大文本与完整参数不落库。
 * 聊天链路 persist=false，只经 SSE 推送不写表。
 */
@Entity
@Table(name = "agent_trace", indexes = {
        @Index(name = "idx_trace_task_created", columnList = "taskId,createdAt"),
        @Index(name = "idx_trace_user_created", columnList = "userId,createdAt") })
public class AgentTraceRecord {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long orgId;

    /** 任务 id（聊天不落库，恒为 null）。 */
    @Column(length = 36)
    private String taskId;

    /** 任务步骤 id（聊天为 null）。 */
    private Integer stepId;

    @Column(nullable = false, length = 36)
    private String traceId;

    @Column(nullable = false, length = 128)
    private String toolName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String inputSummary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String outputSummary;

    /** 原始工具输出长度（判断是否被截断；不存原始内容）。 */
    @Column(nullable = false)
    private int outputLength;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentTraceRecord() {
        // JPA
    }

    public AgentTraceRecord(String id, Long userId, Long orgId, String taskId, Integer stepId,
            String traceId, String toolName, String inputSummary, String outputSummary,
            int outputLength, long durationMs, String status, String error) {
        this.id = id;
        this.userId = userId;
        this.orgId = orgId;
        this.taskId = taskId;
        this.stepId = stepId;
        this.traceId = traceId;
        this.toolName = toolName;
        this.inputSummary = inputSummary;
        this.outputSummary = outputSummary;
        this.outputLength = outputLength;
        this.durationMs = durationMs;
        this.status = status;
        this.error = error;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
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

    public String getTaskId() {
        return taskId;
    }

    public Integer getStepId() {
        return stepId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public int getOutputLength() {
        return outputLength;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
