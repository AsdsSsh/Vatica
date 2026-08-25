package com.example.vatica.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 任务仓库（迭代 5 I5-4）。 */
public interface TaskRecordRepository extends JpaRepository<TaskRecord, String> {

    Optional<TaskRecord> findByIdAndUserId(String id, Long userId);

    /** 迭代 29D：上下文健康查询的双租户归属校验。 */
    Optional<TaskRecord> findByIdAndUserIdAndOrgId(String id, Long userId, Long orgId);

    Optional<TaskRecord> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    List<TaskRecord> findByUserId(Long userId);

    List<TaskRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
