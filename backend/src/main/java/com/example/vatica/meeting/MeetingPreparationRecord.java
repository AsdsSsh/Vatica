package com.example.vatica.meeting;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

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

/**
 * 迭代 24：会议准备的业务事实源。
 *
 * <p>会议标题和时间在创建草案时做快照，之后即使原日程被修改或删除，用户仍能核对本次准备
 * 基于的事实。预览阶段不写工作区或待办；这些副作用只会在 {@link MeetingPreparationStatus#APPLIED}
 * 状态后记录。</p>
 */
@Entity
@Table(name = "meeting_preparation", indexes = {
        @Index(name = "idx_meeting_prep_owner_created", columnList = "userId,createdAt"),
        @Index(name = "idx_meeting_prep_owner_event", columnList = "userId,calendarEventId") })
public class MeetingPreparationRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, updatable = false)
    private Long calendarEventId;

    @Column(nullable = false, length = 500)
    private String meetingTitle;

    @Column(nullable = false)
    private LocalDateTime meetingStartAt;

    @Column(nullable = false)
    private LocalDateTime meetingEndAt;

    @Column(length = 2_000)
    private String userGoal;

    @Column(nullable = false)
    private boolean knowledgeRequested;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MeetingPreparationStatus status;

    /** 24B 保存的结构化预览，不能当作未脱敏模型上下文。 */
    @Column(columnDefinition = "TEXT")
    private String draftJson;

    /** 24C 批准后写入的工作区相对路径。 */
    @Column(length = 500)
    private String documentPath;

    /** 24C 批准后创建的待办 ID，JSON 数组只用于回看和幂等保护。 */
    @Column(columnDefinition = "TEXT")
    private String todoIdsJson;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant appliedAt;

    protected MeetingPreparationRecord() {
        // JPA
    }

    public MeetingPreparationRecord(String id, RequestIdentity identity, Long calendarEventId,
            String meetingTitle, LocalDateTime meetingStartAt, LocalDateTime meetingEndAt,
            String userGoal, boolean knowledgeRequested) {
        this.id = id;
        this.userId = identity.userId();
        this.orgId = identity.orgId();
        this.calendarEventId = calendarEventId;
        this.meetingTitle = meetingTitle;
        this.meetingStartAt = meetingStartAt;
        this.meetingEndAt = meetingEndAt;
        this.userGoal = userGoal;
        this.knowledgeRequested = knowledgeRequested;
        this.status = MeetingPreparationStatus.DRAFT;
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
    public Long getCalendarEventId() { return calendarEventId; }
    public String getMeetingTitle() { return meetingTitle; }
    public LocalDateTime getMeetingStartAt() { return meetingStartAt; }
    public LocalDateTime getMeetingEndAt() { return meetingEndAt; }
    public String getUserGoal() { return userGoal; }
    public boolean isKnowledgeRequested() { return knowledgeRequested; }
    public MeetingPreparationStatus getStatus() { return status; }
    public String getDraftJson() { return draftJson; }
    public String getDocumentPath() { return documentPath; }
    public String getTodoIdsJson() { return todoIdsJson; }
    public String getRejectionReason() { return rejectionReason; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getAppliedAt() { return appliedAt; }

    /** 24B：草案仍处于无副作用阶段，允许用户调整准备目标后重新生成预览。 */
    public void replaceDraft(String goal, boolean requestedKnowledge, String nextDraftJson) {
        if (status != MeetingPreparationStatus.DRAFT) {
            throw new IllegalStateException("操作失败：只有待批准的会议准备可以修改草案。");
        }
        this.userGoal = goal;
        this.knowledgeRequested = requestedKnowledge;
        this.draftJson = nextDraftJson;
        this.errorMessage = null;
    }

    /** 24C：拒绝是终态，明确记录反馈但不产生文档、待办等副作用。 */
    public void reject(String reason) {
        if (status != MeetingPreparationStatus.DRAFT) {
            throw new IllegalStateException("操作失败：只有待批准的会议准备可以拒绝。");
        }
        this.status = MeetingPreparationStatus.REJECTED;
        this.rejectionReason = reason;
    }

    /** 24C：所有计划内写入完成后才标记已应用，后续批准请求可直接幂等返回。 */
    public void markApplied(String nextDocumentPath, String nextTodoIdsJson) {
        if (status != MeetingPreparationStatus.DRAFT) {
            throw new IllegalStateException("操作失败：会议准备不再处于待批准状态。");
        }
        this.status = MeetingPreparationStatus.APPLIED;
        this.documentPath = nextDocumentPath;
        this.todoIdsJson = nextTodoIdsJson;
        this.appliedAt = Instant.now();
        this.errorMessage = null;
    }

    /** 文件写入失败时保留草案和已知路径，供用户定位而不掩盖为成功。 */
    public void markFailed(String nextDocumentPath, String message) {
        if (status != MeetingPreparationStatus.DRAFT) {
            throw new IllegalStateException("操作失败：只有待批准的会议准备可以标记失败。");
        }
        this.status = MeetingPreparationStatus.FAILED;
        this.documentPath = nextDocumentPath;
        this.errorMessage = message;
    }
}
