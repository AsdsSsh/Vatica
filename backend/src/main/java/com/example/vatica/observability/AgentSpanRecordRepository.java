package com.example.vatica.observability;

import java.util.List;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 迭代 21A：Span 查询严格按用户与组织双重租户条件收口。 */
public interface AgentSpanRecordRepository extends JpaRepository<AgentSpanRecord, String>,
        JpaSpecificationExecutor<AgentSpanRecord> {

    List<AgentSpanRecord> findByUserIdAndOrgIdAndTraceIdOrderByStartedAtAscSpanIdAsc(
            Long userId, Long orgId, String traceId);

    List<AgentSpanRecord> findByUserIdAndOrgIdAndTaskIdOrderByStartedAtAscSpanIdAsc(
            Long userId, Long orgId, String taskId);

    List<AgentSpanRecord> findTop500ByUserIdAndOrgIdOrderByStartedAtDescSpanIdDesc(
            Long userId, Long orgId);

    long deleteByStartedAtBefore(Instant cutoff);
}
