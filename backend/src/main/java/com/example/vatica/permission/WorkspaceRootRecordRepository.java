package com.example.vatica.permission;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceRootRecordRepository extends JpaRepository<WorkspaceRootRecord, Long> {
    List<WorkspaceRootRecord> findByUserId(Long userId);
    @Transactional void deleteByUserId(Long userId);
}
