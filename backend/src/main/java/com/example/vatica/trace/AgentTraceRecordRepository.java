package com.example.vatica.trace;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 迭代 15 I15-1：任务执行轨迹持久化查询。 */
public interface AgentTraceRecordRepository extends JpaRepository<AgentTraceRecord, String> {

    List<AgentTraceRecord> findByTaskIdOrderByCreatedAtAscIdAsc(String taskId);

    /** 迭代 18B：按租户读取任务工具 trace，供固定任务集的工具调用基线使用。 */
    @Query("select r from AgentTraceRecord r where r.userId = :userId and r.taskId is not null")
    List<AgentTraceRecord> findTaskTracesByUserId(@Param("userId") Long userId);
}
