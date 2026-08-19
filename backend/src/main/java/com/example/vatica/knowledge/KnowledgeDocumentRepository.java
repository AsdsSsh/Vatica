package com.example.vatica.knowledge;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentRecord, Long> {

    Optional<KnowledgeDocumentRecord> findByOrgIdAndUserIdAndSourcePath(Long orgId, Long userId, String sourcePath);

    Optional<KnowledgeDocumentRecord> findByIdAndOrgIdAndUserId(Long id, Long orgId, Long userId);

    @Query("select d from KnowledgeDocumentRecord d where d.orgId = :orgId "
            + "and (d.userId = :userId or d.visibility = com.example.vatica.knowledge.KnowledgeVisibility.ORG_SHARED) "
            + "order by d.updatedAt desc")
    List<KnowledgeDocumentRecord> findVisible(@Param("orgId") Long orgId, @Param("userId") Long userId);
}
