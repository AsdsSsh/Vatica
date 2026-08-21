package com.example.vatica.report;

import java.time.Instant;

import com.example.vatica.auth.RequestIdentity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** 迭代 26B：保存周报事实快照与用户编辑字段，避免草案刷新时重新取数。 */
@Entity
@Table(name = "weekly_report_draft", indexes = {
        @Index(name = "idx_weekly_report_owner_updated", columnList = "userId,updatedAt") })
public class WeeklyReportDraftRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String focus;

    @Column(columnDefinition = "TEXT")
    private String risks;

    @Column(columnDefinition = "TEXT")
    private String nextPlan;

    @Column(nullable = false)
    private boolean wordRequested;

    @Column(nullable = false)
    private boolean excelRequested;

    /** 26A WeeklyReportView 的不可变快照；不是模型思维链。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String factsJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WeeklyReportDraftRecord() {
    }

    public WeeklyReportDraftRecord(String id, RequestIdentity identity, String title, String focus, String risks,
            String nextPlan, boolean wordRequested, boolean excelRequested, String factsJson) {
        this.id = id;
        this.userId = identity.userId();
        this.orgId = identity.orgId();
        this.status = "DRAFT";
        this.title = title;
        this.focus = focus;
        this.risks = risks;
        this.nextPlan = nextPlan;
        this.wordRequested = wordRequested;
        this.excelRequested = excelRequested;
        this.factsJson = factsJson;
    }

    public void update(String title, String focus, String risks, String nextPlan,
            boolean wordRequested, boolean excelRequested) {
        if (!"DRAFT".equals(status)) {
            throw new IllegalStateException("操作失败：只有草案状态的周报可以编辑。");
        }
        this.title = title;
        this.focus = focus;
        this.risks = risks;
        this.nextPlan = nextPlan;
        this.wordRequested = wordRequested;
        this.excelRequested = excelRequested;
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

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getOrgId() { return orgId; }
    public String getStatus() { return status; }
    public String getTitle() { return title; }
    public String getFocus() { return focus; }
    public String getRisks() { return risks; }
    public String getNextPlan() { return nextPlan; }
    public boolean isWordRequested() { return wordRequested; }
    public boolean isExcelRequested() { return excelRequested; }
    public String getFactsJson() { return factsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
