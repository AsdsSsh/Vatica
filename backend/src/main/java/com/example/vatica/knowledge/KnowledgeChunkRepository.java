package com.example.vatica.knowledge;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkRecord, Long> {
    List<KnowledgeChunkRecord> findByDocumentIdOrderByOrdinal(Long documentId);
    void deleteByDocumentId(Long documentId);
}
