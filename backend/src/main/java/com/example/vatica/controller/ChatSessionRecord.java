package com.example.vatica.controller;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 迭代 14：跨设备同步的用户会话元数据。 */
@Entity
@Table(name = "vatica_chat_session",
        uniqueConstraints = @UniqueConstraint(name = "uk_session_owner_id", columnNames = { "userId", "sessionId" }),
        indexes = @Index(name = "idx_session_owner_updated", columnList = "userId,updatedAt"))
public class ChatSessionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 80)
    private String title;

    /** 迭代 15 I15-9：中期滚动摘要。 */
    @Column(columnDefinition = "TEXT")
    private String summaryText;

    /** 已并入摘要的最大消息 seq（水位线；0 = 尚未摘要）。 */
    @Column(nullable = false)
    private long summaryThroughSeq = 0;

    /** 当前摘要的 token 估算（观测用）。 */
    @Column(nullable = false)
    private int summaryTokens = 0;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ChatSessionRecord() {
    }

    public ChatSessionRecord(Long userId, Long orgId, String sessionId, String title) {
        this.userId = userId;
        this.orgId = orgId;
        this.sessionId = sessionId;
        this.title = title;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setTitle(String title) { this.title = title; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public long getSummaryThroughSeq() { return summaryThroughSeq; }
    public void setSummaryThroughSeq(long summaryThroughSeq) { this.summaryThroughSeq = summaryThroughSeq; }
    public int getSummaryTokens() { return summaryTokens; }
    public void setSummaryTokens(int summaryTokens) { this.summaryTokens = summaryTokens; }
}
