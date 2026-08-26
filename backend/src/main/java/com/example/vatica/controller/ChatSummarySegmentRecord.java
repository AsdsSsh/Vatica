package com.example.vatica.controller;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 迭代 31B：会话摘要的不可变局部段。
 *
 * <p>摘要段是由原始聊天消息派生的可重建缓存，不保存思维链、完整 Prompt 或工具原文。
 * 成功写入后不更新、不覆盖；同一消息范围只能有一个同层级段，方便后续按范围追溯和重建。</p>
 */
@Entity
@Table(name = "vatica_chat_summary_segment", uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_summary_segment_range",
        columnNames = { "org_id", "user_id", "session_id", "summary_level", "start_seq", "end_seq" }), indexes = {
                @Index(name = "idx_chat_summary_segment_scope_range",
                        columnList = "org_id,user_id,session_id,summary_level,start_seq,end_seq"),
                @Index(name = "idx_chat_summary_segment_scope_created",
                        columnList = "org_id,user_id,session_id,created_at") })
public class ChatSummarySegmentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, updatable = false, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_level", nullable = false, updatable = false, length = 16)
    private ChatSummarySegmentLevel summaryLevel;

    @Column(name = "start_seq", nullable = false, updatable = false)
    private long startSeq;

    @Column(name = "end_seq", nullable = false, updatable = false)
    private long endSeq;

    /** 摘要正文；原始聊天仍由 {@code vatica_chat_message} 保存。 */
    @Column(name = "summary_text", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "estimated_tokens", nullable = false, updatable = false)
    private int estimatedTokens;

    @Column(name = "source_message_count", nullable = false, updatable = false)
    private int sourceMessageCount;

    /** 对本批原始消息序号、角色和正文计算的 SHA-256；用于审计和失效检测，不含正文。 */
    @Column(name = "source_fingerprint", nullable = false, updatable = false, length = 64)
    private String sourceFingerprint;

    /** 生成提示与裁剪策略版本，便于将来重建，不作为模型输出事实。 */
    @Column(name = "strategy_version", nullable = false, updatable = false, length = 64)
    private String strategyVersion;

    @Column(name = "model_id", nullable = false, updatable = false, length = 160)
    private String modelId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatSummarySegmentRecord() {
        // JPA
    }

    public ChatSummarySegmentRecord(Long orgId, Long userId, String sessionId,
            ChatSummarySegmentLevel summaryLevel, long startSeq, long endSeq, String text,
            int estimatedTokens, int sourceMessageCount, String sourceFingerprint,
            String strategyVersion, String modelId) {
        this.orgId = orgId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.summaryLevel = summaryLevel;
        this.startSeq = startSeq;
        this.endSeq = endSeq;
        this.text = text;
        this.estimatedTokens = estimatedTokens;
        this.sourceMessageCount = sourceMessageCount;
        this.sourceFingerprint = sourceFingerprint;
        this.strategyVersion = strategyVersion;
        this.modelId = modelId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getOrgId() { return orgId; }
    public Long getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public ChatSummarySegmentLevel getSummaryLevel() { return summaryLevel; }
    public long getStartSeq() { return startSeq; }
    public long getEndSeq() { return endSeq; }
    public String getText() { return text; }
    public int getEstimatedTokens() { return estimatedTokens; }
    public int getSourceMessageCount() { return sourceMessageCount; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getModelId() { return modelId; }
    public Instant getCreatedAt() { return createdAt; }
}
