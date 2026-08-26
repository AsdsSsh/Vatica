package com.example.vatica.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ChatSessionRecordRepository extends JpaRepository<ChatSessionRecord, Long> {

    Optional<ChatSessionRecord> findByUserIdAndSessionId(Long userId, String sessionId);

    /** 迭代 29D：健康查询必须同时绑定用户和组织，避免同一用户跨组织读取会话元数据。 */
    Optional<ChatSessionRecord> findByUserIdAndOrgIdAndSessionId(Long userId, Long orgId, String sessionId);

    /** 摘要成功/失败与会话删除用同一行锁串行化，远程模型调用不持锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from ChatSessionRecord s
            where s.userId = :userId and s.orgId = :orgId and s.sessionId = :sessionId
            """)
    Optional<ChatSessionRecord> findForUpdate(@Param("userId") Long userId,
            @Param("orgId") Long orgId, @Param("sessionId") String sessionId);

    List<ChatSessionRecord> findByOrgIdAndUserIdOrderByUpdatedAtDesc(Long orgId, Long userId);

    List<ChatSessionRecord> findByUserIdOrderByUpdatedAtDesc(Long userId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);

    void deleteByOrgIdAndUserIdAndSessionId(Long orgId, Long userId, String sessionId);
}
