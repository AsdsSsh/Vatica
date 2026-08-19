package com.example.vatica.knowledge;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 文档元数据；原文仍由工作区文件负责，数据库只保存可追溯索引元数据。 */
@Entity
@Table(name = "vatica_knowledge_document", indexes = {
        @Index(name = "idx_knowledge_document_scope", columnList = "org_id,user_id,status"),
        @Index(name = "idx_knowledge_document_path", columnList = "org_id,user_id,source_path")
})
public class KnowledgeDocumentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeVisibility visibility;

    @Column(name = "source_path", nullable = false, length = 1024)
    private String sourcePath;

    @Column(name = "source_name", nullable = false, length = 255)
    private String sourceName;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeDocumentStatus status;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeDocumentRecord() {
    }

    public KnowledgeDocumentRecord(Long orgId, Long userId, KnowledgeVisibility visibility,
            String sourcePath, String sourceName, String contentHash) {
        this.orgId = orgId;
        this.userId = userId;
        this.visibility = visibility;
        this.sourcePath = sourcePath;
        this.sourceName = sourceName;
        this.contentHash = contentHash;
        this.version = 1;
        this.status = KnowledgeDocumentStatus.INDEXING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void beginReindex(String hash, KnowledgeVisibility nextVisibility, String nextName) {
        this.contentHash = hash;
        this.visibility = nextVisibility;
        this.sourceName = nextName;
        this.version++;
        this.status = KnowledgeDocumentStatus.INDEXING;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void ready() {
        this.status = KnowledgeDocumentStatus.READY;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void failed(String message) {
        this.status = KnowledgeDocumentStatus.FAILED;
        this.errorMessage = message == null ? "索引失败" : message.substring(0, Math.min(message.length(), 2000));
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrgId() { return orgId; }
    public Long getUserId() { return userId; }
    public KnowledgeVisibility getVisibility() { return visibility; }
    public String getSourcePath() { return sourcePath; }
    public String getSourceName() { return sourceName; }
    public String getContentHash() { return contentHash; }
    public int getVersion() { return version; }
    public KnowledgeDocumentStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
