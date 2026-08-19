package com.example.vatica.observability;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Agent 全链路 Span（迭代 21A）。只保存诊断所需的元数据、脱敏摘要和用量，
 * 不保存原始 prompt、原始响应或原始思维链。
 */
@Entity
@Table(name = "agent_span", indexes = {
        @Index(name = "idx_span_tenant_started", columnList = "userId,orgId,startedAt"),
        @Index(name = "idx_span_task_started", columnList = "userId,orgId,taskId,startedAt"),
        @Index(name = "idx_span_trace_started", columnList = "userId,orgId,traceId,startedAt") })
public class AgentSpanRecord {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @Column(length = 36)
    private String spanId;

    @Column(nullable = false, length = 36)
    private String traceId;

    @Column(length = 36)
    private String parentSpanId;

    @Column(nullable = false, length = 36)
    private String runId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long orgId;

    @Column(length = 36)
    private String taskId;

    private Integer stepId;

    @Column(nullable = false)
    private int attempt;

    @Column(nullable = false, length = 32)
    private String spanType;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 32)
    private String runtime;

    @Column(length = 64)
    private String agentId;

    @Column(length = 96)
    private String role;

    @Column(length = 64)
    private String modelSlotId;

    @Column(length = 64)
    private String skillId;

    @Column(length = 32)
    private String skillVersion;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    @Column(nullable = false)
    private long durationMs;

    @Column(columnDefinition = "TEXT")
    private String inputSummary;

    @Column(columnDefinition = "TEXT")
    private String outputSummary;

    @Column(length = 48)
    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String errorSummary;

    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Integer reasoningTokens;
    private Double contextFillRatio;
    private Double costEstimate;
    private Integer judgeScore;

    @Column(length = 16)
    private String judgeVerdict;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentSpanRecord() {
        // JPA
    }

    public AgentSpanRecord(String spanId, String traceId, String parentSpanId, String runId,
            Long userId, Long orgId, String taskId, Integer stepId, int attempt,
            String spanType, String name, String runtime, String agentId, String role,
            String modelSlotId, String skillId, String skillVersion, String inputSummary) {
        this.spanId = spanId;
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.runId = runId;
        this.userId = userId;
        this.orgId = orgId;
        this.taskId = taskId;
        this.stepId = stepId;
        this.attempt = attempt;
        this.spanType = spanType;
        this.name = name;
        this.runtime = runtime;
        this.agentId = agentId;
        this.role = role;
        this.modelSlotId = modelSlotId;
        this.skillId = skillId;
        this.skillVersion = skillVersion;
        this.inputSummary = inputSummary;
        this.status = STATUS_OPEN;
        this.startedAt = Instant.now();
        this.durationMs = 0;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (startedAt == null) {
            startedAt = createdAt;
        }
    }

    public void finish(String status, Instant endedAt, long durationMs, String outputSummary,
            String errorCode, String errorSummary, Integer inputTokens, Integer outputTokens,
            Integer totalTokens, Integer reasoningTokens, Double contextFillRatio,
            Double costEstimate, Integer judgeScore, String judgeVerdict) {
        this.status = status;
        this.endedAt = endedAt;
        this.durationMs = Math.max(0, durationMs);
        this.outputSummary = outputSummary;
        this.errorCode = errorCode;
        this.errorSummary = errorSummary;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.reasoningTokens = reasoningTokens;
        this.contextFillRatio = contextFillRatio;
        this.costEstimate = costEstimate;
        this.judgeScore = judgeScore;
        this.judgeVerdict = judgeVerdict;
    }

    public String getSpanId() { return spanId; }
    public String getTraceId() { return traceId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getRunId() { return runId; }
    public Long getUserId() { return userId; }
    public Long getOrgId() { return orgId; }
    public String getTaskId() { return taskId; }
    public Integer getStepId() { return stepId; }
    public int getAttempt() { return attempt; }
    public String getSpanType() { return spanType; }
    public String getName() { return name; }
    public String getRuntime() { return runtime; }
    public String getAgentId() { return agentId; }
    public String getRole() { return role; }
    public String getModelSlotId() { return modelSlotId; }
    public String getSkillId() { return skillId; }
    public String getSkillVersion() { return skillVersion; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public long getDurationMs() { return durationMs; }
    public String getInputSummary() { return inputSummary; }
    public String getOutputSummary() { return outputSummary; }
    public String getErrorCode() { return errorCode; }
    public String getErrorSummary() { return errorSummary; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public Integer getReasoningTokens() { return reasoningTokens; }
    public Double getContextFillRatio() { return contextFillRatio; }
    public Double getCostEstimate() { return costEstimate; }
    public Integer getJudgeScore() { return judgeScore; }
    public String getJudgeVerdict() { return judgeVerdict; }
    public Instant getCreatedAt() { return createdAt; }
}
