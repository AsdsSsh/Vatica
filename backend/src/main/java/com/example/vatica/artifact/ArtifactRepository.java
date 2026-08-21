package com.example.vatica.artifact;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRepository extends JpaRepository<ArtifactRecord, String> {
    Optional<ArtifactRecord> findByUserIdAndArtifactKey(Long userId, String artifactKey);
    List<ArtifactRecord> findByUserIdAndSubjectTypeAndSubjectIdOrderByUpdatedAtDesc(Long userId, String subjectType,
            String subjectId);
}
