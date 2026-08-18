package com.example.vatica.usage;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 迭代 15 I15-13：用量聚合查询。 */
public interface UsageRecordRepository extends JpaRepository<UsageRecord, String> {

    List<UsageRecord> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, Instant after);

    List<UsageRecord> findByUserIdAndRequestIdOrderByCreatedAtAsc(Long userId, String requestId);

    /** 迭代 18B：按租户读取任务级 token 维度，避免把其他用户的模型用量混入基线。 */
    @Query("select r from UsageRecord r where r.userId = :userId and r.taskId is not null")
    List<UsageRecord> findTaskUsageByUserId(@Param("userId") Long userId);
}
