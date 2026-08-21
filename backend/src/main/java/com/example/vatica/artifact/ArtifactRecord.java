package com.example.vatica.artifact;

import java.time.Instant;

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
import jakarta.persistence.UniqueConstraint;

/** 迭代 25C：用户可回看的文档、待办、草案和失败记录索引。 */
@Entity
@Table(name = "vatica_artifact", uniqueConstraints = @UniqueConstraint(name = "uk_artifact_owner_key",
        columnNames = { "userId", "artifactKey" }), indexes = {
                @Index(name = "idx_artifact_owner_subject", columnList = "userId,subjectType,subjectId,updatedAt"),
                @Index(name = "idx_artifact_owner_type_status", columnList = "userId,type,status,updatedAt") })
public class ArtifactRecord {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, updatable = false, length = 64)
    private String subjectType;

    @Column(nullable = false, updatable = false, length = 128)
    private String subjectId;

    @Column(nullable = false, updatable = false, length = 64)
    private String type;

    @Column(nullable = false, updatable = false, length = 220)
    private String artifactKey;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(length = 1_000)
    private String locator;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ArtifactStatus status;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 100)
    private String sourceActionId;

    @Column(length = 200)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ArtifactRecord() {
        // JPA
    }

    public ArtifactRecord(String id, RequestIdentity identity, String subjectType, String subjectId,
            String type, String artifactKey, String name, String locator, ArtifactStatus status, String summary,
            String sourceActionId, String idempotencyKey) {
        this.id = id;
        this.userId = identity.userId();
        this.orgId = identity.orgId();
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.type = type;
        this.artifactKey = artifactKey;
        this.name = name;
        this.locator = locator;
        this.status = status;
        this.summary = summary;
        this.sourceActionId = sourceActionId;
        this.idempotencyKey = idempotencyKey;
    }

    public void update(String nextName, String nextLocator, ArtifactStatus nextStatus, String nextSummary,
            String nextSourceActionId, String nextIdempotencyKey) {
        this.name = nextName;
        this.locator = nextLocator;
        this.status = nextStatus;
        this.summary = nextSummary;
        this.sourceActionId = nextSourceActionId;
        this.idempotencyKey = nextIdempotencyKey;
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
    public String getSubjectType() { return subjectType; }
    public String getSubjectId() { return subjectId; }
    public String getType() { return type; }
    public String getArtifactKey() { return artifactKey; }
    public String getName() { return name; }
    public String getLocator() { return locator; }
    public ArtifactStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public String getSourceActionId() { return sourceActionId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
