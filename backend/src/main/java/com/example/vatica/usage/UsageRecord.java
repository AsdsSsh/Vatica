package com.example.vatica.usage;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** 迭代 15 I15-13：用量观测表——只存元数据，不存消息内容、工具参数、key 片段。 */
@Entity
@Table(name = "vatica_usage", indexes = {
        @Index(name = "idx_usage_user_created", columnList = "userId,createdAt"),
        @Index(name = "idx_usage_request", columnList = "requestId") })
public class UsageRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String requestId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 24)
    private String requestType;

    @Column(length = 64)
    private String slotId;

    @Column(length = 36)
    private String taskId;

    private Integer stepId;

    @Column(length = 16)
    private String reasoningMode;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private int totalTokens;

    private int reasoningTokens;

    private long cacheReadTokens;

    private long cacheWriteTokens;

    /** 上下文水位百分比（0-100；只记水位不记内容）。 */
    private Integer contextFillRatio;

    @Column(nullable = false)
    private long durationMs;

    private double costEstimate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected UsageRecord() {
    }

    public UsageRecord(String id, String requestId, Long userId, Long orgId, String requestType,
            String slotId, String taskId, Integer stepId, String reasoningMode,
            int inputTokens, int outputTokens, int totalTokens, int reasoningTokens,
            long cacheReadTokens, long cacheWriteTokens, Integer contextFillRatio,
            long durationMs, double costEstimate) {
        this.id = id;
        this.requestId = requestId;
        this.userId = userId;
        this.orgId = orgId;
        this.requestType = requestType;
        this.slotId = slotId;
        this.taskId = taskId;
        this.stepId = stepId;
        this.reasoningMode = reasoningMode;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.reasoningTokens = reasoningTokens;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.contextFillRatio = contextFillRatio;
        this.durationMs = durationMs;
        this.costEstimate = costEstimate;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public Long getUserId() { return userId; }
    public Long getOrgId() { return orgId; }
    public String getRequestType() { return requestType; }
    public String getSlotId() { return slotId; }
    public String getTaskId() { return taskId; }
    public Integer getStepId() { return stepId; }
    public String getReasoningMode() { return reasoningMode; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getTotalTokens() { return totalTokens; }
    public int getReasoningTokens() { return reasoningTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public Integer getContextFillRatio() { return contextFillRatio; }
    public long getDurationMs() { return durationMs; }
    public double getCostEstimate() { return costEstimate; }
    public Instant getCreatedAt() { return createdAt; }
}
