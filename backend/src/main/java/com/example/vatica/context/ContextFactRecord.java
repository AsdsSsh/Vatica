package com.example.vatica.context;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 迭代 29B：可追溯关键事实索引。
 *
 * <p>事实不是聊天正文、工具原文或模型思维链。每次事实变化都会生成新的 revision，
 * 旧版本保留为 SUPERSEDED；撤销也只改变状态，便于审计和上下文重建。</p>
 */
@Entity
@Table(name = "vatica_context_fact", uniqueConstraints = @UniqueConstraint(
        name = "uk_context_fact_revision",
        columnNames = { "org_id", "user_id", "scope_type", "scope_id", "fact_key", "revision" }), indexes = {
                @Index(name = "idx_context_fact_scope", columnList = "org_id,user_id,scope_type,scope_id,status,updated_at"),
                @Index(name = "idx_context_fact_subject", columnList = "org_id,user_id,subject_type,subject_id,status"),
                @Index(name = "idx_context_fact_source", columnList = "org_id,user_id,source_type,source_id") })
public class ContextFactRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, updatable = false, length = 24)
    private ContextFactScopeType scopeType;

    @Column(name = "scope_id", nullable = false, updatable = false, length = 128)
    private String scopeId;

    @Column(name = "subject_type", updatable = false, length = 64)
    private String subjectType;

    @Column(name = "subject_id", updatable = false, length = 128)
    private String subjectId;

    @Column(name = "fact_key", nullable = false, updatable = false, length = 160)
    private String factKey;

    @Column(nullable = false, updatable = false)
    private int revision;

    @Column(name = "supersedes_fact_id", updatable = false, length = 36)
    private String supersedesFactId;

    @Column(name = "superseded_by_fact_id", length = 36)
    private String supersededByFactId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, updatable = false, length = 32)
    private ContextFactType factType;

    /** 只保存受控小 JSON；大文本、附件和工具原文必须留在来源记录或产物中。 */
    @Column(name = "value_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String valueJson;

    @Column(name = "display_summary", nullable = false, updatable = false, length = 500)
    private String displaySummary;

    @Column(name = "value_hash", nullable = false, updatable = false, length = 64)
    private String valueHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContextFactStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, updatable = false, length = 24)
    private ContextFactTrustLevel trustLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 24)
    private ContextFactVerificationState verificationState;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 24)
    private ContextFactSourceType sourceType;

    @Column(name = "source_id", nullable = false, updatable = false, length = 160)
    private String sourceId;

    @Column(name = "source_version", updatable = false, length = 128)
    private String sourceVersion;

    @Column(name = "source_fingerprint", updatable = false, length = 128)
    private String sourceFingerprint;

    /** 只保存脱敏证据定位，不保存引用原文。 */
    @Column(name = "evidence_refs_json", columnDefinition = "TEXT")
    private String evidenceRefsJson;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContextFactRecord() {
        // JPA
    }

    public ContextFactRecord(String id, Long orgId, Long userId, ContextFactScopeType scopeType, String scopeId,
            String subjectType, String subjectId, String factKey, int revision, String supersedesFactId,
            ContextFactType factType, String valueJson, String displaySummary, String valueHash,
            ContextFactTrustLevel trustLevel, ContextFactVerificationState verificationState,
            ContextFactSourceType sourceType, String sourceId, String sourceVersion, String sourceFingerprint,
            String evidenceRefsJson, Instant observedAt, Instant verifiedAt, Instant validUntil) {
        this.id = id;
        this.orgId = orgId;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.factKey = factKey;
        this.revision = revision;
        this.supersedesFactId = supersedesFactId;
        this.factType = factType;
        this.valueJson = valueJson;
        this.displaySummary = displaySummary;
        this.valueHash = valueHash;
        this.status = ContextFactStatus.ACTIVE;
        this.trustLevel = trustLevel;
        this.verificationState = verificationState;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceVersion = sourceVersion;
        this.sourceFingerprint = sourceFingerprint;
        this.evidenceRefsJson = evidenceRefsJson;
        this.observedAt = observedAt;
        this.verifiedAt = verifiedAt;
        this.validUntil = validUntil;
    }

    /** 将当前版本标为被新版本替代；原记录仍保留以支持审计。 */
    public void supersede(String replacementId) {
        if (status == ContextFactStatus.REVOKED) {
            return;
        }
        status = ContextFactStatus.SUPERSEDED;
        supersededByFactId = replacementId;
        statusReason = "被事实版本 " + replacementId + " 替代";
    }

    /** 撤销事实，不删除历史版本，也不伪造来源失效前的执行结果。 */
    public void revoke(String reason) {
        status = ContextFactStatus.REVOKED;
        verificationState = ContextFactVerificationState.REVOKED;
        statusReason = reason;
        revokedAt = Instant.now();
    }

    /** 来源记录发生变化时，保留事实审计链但阻止它继续进入当前上下文。 */
    public void markNeedsRefresh(String reason) {
        if (status != ContextFactStatus.ACTIVE || verificationState == ContextFactVerificationState.REVOKED) {
            return;
        }
        verificationState = ContextFactVerificationState.NEEDS_REFRESH;
        statusReason = reason;
    }

    public String getId() { return id; }
    public Long getOrgId() { return orgId; }
    public Long getUserId() { return userId; }
    public ContextFactScopeType getScopeType() { return scopeType; }
    public String getScopeId() { return scopeId; }
    public String getSubjectType() { return subjectType; }
    public String getSubjectId() { return subjectId; }
    public String getFactKey() { return factKey; }
    public int getRevision() { return revision; }
    public String getSupersedesFactId() { return supersedesFactId; }
    public String getSupersededByFactId() { return supersededByFactId; }
    public ContextFactType getFactType() { return factType; }
    public String getValueJson() { return valueJson; }
    public String getDisplaySummary() { return displaySummary; }
    public String getValueHash() { return valueHash; }
    public ContextFactStatus getStatus() { return status; }
    public ContextFactTrustLevel getTrustLevel() { return trustLevel; }
    public ContextFactVerificationState getVerificationState() { return verificationState; }
    public ContextFactSourceType getSourceType() { return sourceType; }
    public String getSourceId() { return sourceId; }
    public String getSourceVersion() { return sourceVersion; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public String getEvidenceRefsJson() { return evidenceRefsJson; }
    public Instant getObservedAt() { return observedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getValidUntil() { return validUntil; }
    public String getStatusReason() { return statusReason; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = ContextFactStatus.ACTIVE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
