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
import jakarta.persistence.UniqueConstraint;

/** 迭代 26C：周报导出计划和冻结快照；实际写入必须经过批准动作。 */
@Entity
@Table(name = "weekly_report_export", uniqueConstraints = @UniqueConstraint(name = "uk_weekly_report_export_owner_draft",
        columnNames = { "userId", "draftId" }), indexes = {
                @Index(name = "idx_weekly_report_export_owner_updated", columnList = "userId,updatedAt") })
public class WeeklyReportExportRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, updatable = false, length = 36)
    private String draftId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String planJson;

    /** 导出计划创建时的草案视图，批准后不受草案再次编辑影响。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(nullable = false)
    private boolean wordRequested;

    @Column(nullable = false)
    private boolean excelRequested;

    @Column(nullable = false)
    private boolean mailRequested;

    @Column(length = 1_000)
    private String mailTo;

    @Column(length = 240)
    private String mailSubject;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WeeklyReportExportRecord() {
    }

    public WeeklyReportExportRecord(String id, RequestIdentity identity, String draftId, String planJson,
            String snapshotJson, boolean wordRequested, boolean excelRequested, boolean mailRequested,
            String mailTo, String mailSubject) {
        this.id = id;
        this.userId = identity.userId();
        this.orgId = identity.orgId();
        this.draftId = draftId;
        this.status = "DRAFT";
        this.planJson = planJson;
        this.snapshotJson = snapshotJson;
        this.wordRequested = wordRequested;
        this.excelRequested = excelRequested;
        this.mailRequested = mailRequested;
        this.mailTo = mailTo;
        this.mailSubject = mailSubject;
    }

    public void markApproved() {
        if (!"DRAFT".equals(status)) {
            throw new IllegalStateException("操作失败：导出计划不处于待批准状态。");
        }
        status = "APPROVED";
        errorMessage = null;
    }

    public void markApplied() {
        status = "APPLIED";
        errorMessage = null;
    }

    public void markFailed(String message) {
        status = "FAILED";
        errorMessage = message;
    }

    public void cancel() {
        if ("APPLIED".equals(status)) {
            throw new IllegalStateException("操作失败：已完成的周报导出不能取消。");
        }
        status = "CANCELLED";
        errorMessage = "已取消未开始的导出动作；已完成的文件仍保留。";
    }

    public void resumeForRetry() {
        if (!"FAILED".equals(status)) {
            throw new IllegalStateException("操作失败：只有失败的周报导出可以重试。");
        }
        status = "APPROVED";
        errorMessage = null;
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
    public String getDraftId() { return draftId; }
    public String getStatus() { return status; }
    public String getPlanJson() { return planJson; }
    public String getSnapshotJson() { return snapshotJson; }
    public boolean isWordRequested() { return wordRequested; }
    public boolean isExcelRequested() { return excelRequested; }
    public boolean isMailRequested() { return mailRequested; }
    public String getMailTo() { return mailTo; }
    public String getMailSubject() { return mailSubject; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
