package com.example.vatica.permission;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRuleRecordRepository extends JpaRepository<PermissionRuleRecord, Long> {
    List<PermissionRuleRecord> findByUserId(Long userId);
    boolean existsByUserIdAndPathAndAccess(Long userId, String path, FileAccess access);
}
