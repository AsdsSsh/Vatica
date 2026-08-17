package com.example.vatica.usage;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 15 I15-13：用量聚合查询。 */
public interface UsageRecordRepository extends JpaRepository<UsageRecord, String> {

    List<UsageRecord> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, Instant after);

    List<UsageRecord> findByUserIdAndRequestIdOrderByCreatedAtAsc(Long userId, String requestId);
}
