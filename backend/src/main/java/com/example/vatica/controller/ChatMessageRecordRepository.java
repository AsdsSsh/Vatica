package com.example.vatica.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 迭代 31C：在近期窗口之前按字面量检索会话原文。
     * LOCATE 不把用户输入中的 % / _ 当作 LIKE 通配符，参数绑定也避免动态 SQL 注入。
     */
    @Query("""
            select m from ChatMessageRecord m
            where m.orgId = :orgId and m.userId = :userId and m.sessionId = :sessionId
              and m.seq >= :afterSeq and m.seq < :beforeSeq
              and locate(lower(:term), lower(m.content)) > 0
            order by m.seq desc, m.id desc
            """)
    List<ChatMessageRecord> searchHistoricalEvidence(@Param("orgId") Long orgId,
            @Param("userId") Long userId, @Param("sessionId") String sessionId,
            @Param("afterSeq") long afterSeq, @Param("beforeSeq") long beforeSeq,
            @Param("term") String term, Pageable pageable);

    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdAndSeqLessThanOrderBySeqDescIdDesc(
            Long orgId, Long userId, String sessionId, long seq, Pageable pageable);

    List<ChatMessageRecord> findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqAscIdAsc(
            Long orgId, Long userId, String sessionId, long seq, long beforeSeq, Pageable pageable);

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
