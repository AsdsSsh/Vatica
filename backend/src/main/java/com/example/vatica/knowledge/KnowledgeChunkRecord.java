package com.example.vatica.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 文档切片及原文位置；向量本体由 pgvector 适配器按 chunk_id 保存。 */
@Entity
@Table(name = "vatica_knowledge_chunk", indexes = {
        @Index(name = "idx_knowledge_chunk_document", columnList = "document_id,ordinal_no")
})
public class KnowledgeChunkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "ordinal_no", nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(length = 255)
    private String heading;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    protected KnowledgeChunkRecord() {
    }

    public KnowledgeChunkRecord(Long documentId, int ordinal, String text, String heading,
            int startOffset, int endOffset) {
        this.documentId = documentId;
        this.ordinal = ordinal;
        this.text = text;
        this.heading = heading;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public int getOrdinal() { return ordinal; }
    public String getText() { return text; }
    public String getHeading() { return heading; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
}
