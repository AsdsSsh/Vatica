package com.example.vatica.controller;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 迭代 31B：摘要段的读取必须同时绑定组织、用户和会话。
 *
 * <p>不提供仅按会话 ID 或仅按用户的派生查询，避免将可回灌的历史摘要跨租户混用。</p>
 */
public interface ChatSummarySegmentRecordRepository extends JpaRepository<ChatSummarySegmentRecord, Long> {

    List<ChatSummarySegmentRecord> findByOrgIdAndUserIdAndSessionIdOrderByStartSeqAsc(
            Long orgId, Long userId, String sessionId);

    List<ChatSummarySegmentRecord> findByOrgIdAndUserIdAndSessionIdAndSummaryLevelOrderByStartSeqAsc(
            Long orgId, Long userId, String sessionId, ChatSummarySegmentLevel summaryLevel);

    long countByOrgIdAndUserIdAndSessionId(Long orgId, Long userId, String sessionId);

    void deleteByOrgIdAndUserIdAndSessionId(Long orgId, Long userId, String sessionId);
}
