package com.example.vatica.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会话消息仓库（迭代 5 I5-4）。 */
public interface ChatMessageRecordRepository extends JpaRepository<ChatMessageRecord, Long> {

    /** 按 seq 倒序取最近 N 条（Pageable 由调用方给 limit）。 */
    List<ChatMessageRecord> findByUserIdAndSessionIdOrderBySeqDesc(Long userId, String sessionId,
            Pageable pageable);

    /** 迭代 31B：模型按需读原文必须同时绑定组织、用户与会话。 */
    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdOrderBySeqDesc(Long orgId, Long userId,
            String sessionId, Pageable pageable);

    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdOrderBySeqAsc(Long orgId, Long userId,
            String sessionId);

    List<ChatMessageRecord> findByUserIdAndSessionIdOrderBySeqAsc(Long userId, String sessionId);

    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanOrderBySeqAsc(Long orgId,
            Long userId, String sessionId, long seq, Pageable pageable);

    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
            Long orgId, Long userId, String sessionId, long afterSeq, long throughSeq, Pageable pageable);

    long countByOrgIdAndUserIdAndSessionIdAndSeqGreaterThan(Long orgId, Long userId, String sessionId, long seq);

    long countByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThan(Long orgId, Long userId,
            String sessionId, long afterSeq, long beforeSeq);

    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqAsc(
            Long orgId, Long userId, String sessionId, long afterSeq, long beforeSeq, Pageable pageable);

    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqDesc(
            Long orgId, Long userId, String sessionId, long afterSeq, long beforeSeq, Pageable pageable);

    long countByUserIdAndSessionIdAndSeqGreaterThan(Long userId, String sessionId, long seq);

    /** 迭代 29A：只取未摘要区间的受控头尾片段，禁止为 Prompt 全量读取历史。 */
    List<ChatMessageRecord> findByUserIdAndSessionIdAndSeqGreaterThanOrderBySeqAsc(
            Long userId, String sessionId, long seq, Pageable pageable);

    long countByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThan(
            Long userId, String sessionId, long afterSeq, long beforeSeq);

    List<ChatMessageRecord> findByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqAsc(
            Long userId, String sessionId, long afterSeq, long beforeSeq, Pageable pageable);

    List<ChatMessageRecord> findByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqDesc(
            Long userId, String sessionId, long afterSeq, long beforeSeq, Pageable pageable);

    List<ChatMessageRecord> findByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
            Long userId, String sessionId, long afterSeq, long throughSeq, Pageable pageable);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);

    void deleteByOrgIdAndUserIdAndSessionId(Long orgId, Long userId, String sessionId);
}
