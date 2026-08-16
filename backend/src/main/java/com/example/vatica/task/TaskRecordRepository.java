package com.example.vatica.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 任务仓库（迭代 5 I5-4）。 */
public interface TaskRecordRepository extends JpaRepository<TaskRecord, String> {

    Optional<TaskRecord> findByIdAndUserId(String id, Long userId);

    List<TaskRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
