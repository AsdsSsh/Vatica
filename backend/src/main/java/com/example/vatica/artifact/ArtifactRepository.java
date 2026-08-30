package com.example.vatica.artifact;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRepository extends JpaRepository<ArtifactRecord, String> {
    Optional<ArtifactRecord> findByUserIdAndArtifactKey(Long userId, String artifactKey);
    List<ArtifactRecord> findByUserIdAndSubjectTypeAndSubjectIdOrderByUpdatedAtDesc(Long userId, String subjectType,
            String subjectId);

    /** 迭代 33：按当前用户/组织和业务范围读取有限的交付物索引。 */
    List<ArtifactRecord> findByUserIdAndOrgIdAndSubjectIdOrderByUpdatedAtDesc(Long userId, Long orgId,
            String subjectId, Pageable pageable);
}
