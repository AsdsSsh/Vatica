package com.example.vatica.trace;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 15 I15-1：任务执行轨迹持久化查询。 */
public interface AgentTraceRecordRepository extends JpaRepository<AgentTraceRecord, String> {

    List<AgentTraceRecord> findByTaskIdOrderByCreatedAtAscIdAsc(String taskId);
}
