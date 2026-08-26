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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 迭代 14：跨设备同步的用户会话元数据。 */
@Entity
@Table(name = "vatica_chat_session",
        uniqueConstraints = @UniqueConstraint(name = "uk_session_tenant_owner_id",
                columnNames = { "orgId", "userId", "sessionId" }),
        indexes = @Index(name = "idx_session_tenant_owner_updated", columnList = "orgId,userId,updatedAt"))
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

    /** 迭代 29A：摘要是缓存，状态与水位必须可独立审计。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionSummaryStatus summaryStatus = SessionSummaryStatus.PENDING;

    /** 已请求但尚未必然成功覆盖的最大消息序号。 */
    @Column(nullable = false)
    private long summaryRequestedThroughSeq = 0;

    /** 摘要调用次数；失败同样计入，避免后台异常被静默掩盖。 */
    @Column(nullable = false)
    private int summaryAttemptCount = 0;

    private Instant summaryLastAttemptAt;

    private Instant summaryLastSuccessAt;

    /** 自动补偿再次运行的时间；为空代表等待下一条消息或配置变化触发。 */
    private Instant summaryNextRetryAt;

    /** 仅保存脱敏错误分类，禁止把上游错误正文写入会话元数据。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SessionSummaryFailureCode summaryFailureCode = SessionSummaryFailureCode.NONE;

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
    public SessionSummaryStatus getSummaryStatus() {
        return summaryStatus == null ? SessionSummaryStatus.PENDING : summaryStatus;
    }
    public void setSummaryStatus(SessionSummaryStatus summaryStatus) {
        this.summaryStatus = summaryStatus == null ? SessionSummaryStatus.PENDING : summaryStatus;
    }
    public long getSummaryRequestedThroughSeq() { return summaryRequestedThroughSeq; }
    public void setSummaryRequestedThroughSeq(long summaryRequestedThroughSeq) {
        this.summaryRequestedThroughSeq = summaryRequestedThroughSeq;
    }
    public int getSummaryAttemptCount() { return summaryAttemptCount; }
    public void setSummaryAttemptCount(int summaryAttemptCount) { this.summaryAttemptCount = summaryAttemptCount; }
    public Instant getSummaryLastAttemptAt() { return summaryLastAttemptAt; }
    public void setSummaryLastAttemptAt(Instant summaryLastAttemptAt) { this.summaryLastAttemptAt = summaryLastAttemptAt; }
    public Instant getSummaryLastSuccessAt() { return summaryLastSuccessAt; }
    public void setSummaryLastSuccessAt(Instant summaryLastSuccessAt) { this.summaryLastSuccessAt = summaryLastSuccessAt; }
    public Instant getSummaryNextRetryAt() { return summaryNextRetryAt; }
    public void setSummaryNextRetryAt(Instant summaryNextRetryAt) { this.summaryNextRetryAt = summaryNextRetryAt; }
    public SessionSummaryFailureCode getSummaryFailureCode() {
        return summaryFailureCode == null ? SessionSummaryFailureCode.NONE : summaryFailureCode;
    }
    public void setSummaryFailureCode(SessionSummaryFailureCode summaryFailureCode) {
        this.summaryFailureCode = summaryFailureCode == null ? SessionSummaryFailureCode.NONE : summaryFailureCode;
    }
}
