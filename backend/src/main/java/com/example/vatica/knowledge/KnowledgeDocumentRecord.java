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
import jakarta.persistence.UniqueConstraint;

/** 文档元数据；原文仍由工作区文件负责，数据库只保存可追溯索引元数据。 */
@Entity
@Table(name = "vatica_knowledge_document", indexes = {
        @Index(name = "idx_knowledge_document_scope", columnList = "org_id,user_id,status"),
        @Index(name = "idx_knowledge_document_path", columnList = "org_id,user_id,source_path")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_knowledge_document_owner_path", columnNames = { "org_id", "user_id", "source_path" })
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

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "indexed_chunks", nullable = false)
    private int indexedChunks;

    @Column(name = "index_attempt", nullable = false)
    private int indexAttempt;

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
        this.totalChunks = 0;
        this.indexedChunks = 0;
        this.indexAttempt = 1;
        this.status = KnowledgeDocumentStatus.INDEXING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void beginReindex(String hash, KnowledgeVisibility nextVisibility, String nextName) {
        this.contentHash = hash;
        this.visibility = nextVisibility;
        this.sourceName = nextName;
        this.version++;
        this.totalChunks = 0;
        this.indexedChunks = 0;
        this.indexAttempt++;
        this.status = KnowledgeDocumentStatus.INDEXING;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void ready() {
        this.indexedChunks = this.totalChunks;
        this.status = KnowledgeDocumentStatus.READY;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void failed(String message) {
        this.indexedChunks = 0;
        this.status = KnowledgeDocumentStatus.FAILED;
        this.errorMessage = message == null ? "索引失败" : message.substring(0, Math.min(message.length(), 2000));
        this.updatedAt = Instant.now();
    }

    /** 失败恢复沿用同一份内容版本，只递增尝试次数，避免把暂时性失败误报为内容更新。 */
    public void beginRetry(String hash, KnowledgeVisibility nextVisibility, String nextName) {
        this.contentHash = hash;
        this.visibility = nextVisibility;
        this.sourceName = nextName;
        this.indexAttempt++;
        this.totalChunks = 0;
        this.indexedChunks = 0;
        this.status = KnowledgeDocumentStatus.INDEXING;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    /** 为一次新的索引尝试设置确定性的工作量；原文读取和切片仍在工作区内完成。 */
    public void beginIndexing(int totalChunks) {
        this.totalChunks = Math.max(0, totalChunks);
        this.indexedChunks = 0;
        this.status = KnowledgeDocumentStatus.INDEXING;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    /** 新文档第一次导入时写入内容指纹，但不增加已经从构造器开始的版本/尝试次数。 */
    public void beginInitialIndex(String hash, KnowledgeVisibility nextVisibility, String nextName, int totalChunks) {
        this.contentHash = hash;
        this.visibility = nextVisibility;
        this.sourceName = nextName;
        beginIndexing(totalChunks);
    }

    /** 每完成一个切片就推进进度，超过总量时机械截断，避免异常进度。 */
    public void markChunkIndexed(int count) {
        this.indexedChunks = Math.max(0, Math.min(this.totalChunks, count));
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
    public int getTotalChunks() { return totalChunks; }
    public int getIndexedChunks() { return indexedChunks; }
    public int getIndexAttempt() { return indexAttempt; }
    public KnowledgeDocumentStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
