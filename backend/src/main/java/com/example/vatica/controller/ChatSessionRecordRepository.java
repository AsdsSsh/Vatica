package com.example.vatica.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRecordRepository extends JpaRepository<ChatSessionRecord, Long> {

    Optional<ChatSessionRecord> findByUserIdAndSessionId(Long userId, String sessionId);

    /** 迭代 29D：健康查询必须同时绑定用户和组织，避免同一用户跨组织读取会话元数据。 */
    Optional<ChatSessionRecord> findByUserIdAndOrgIdAndSessionId(Long userId, Long orgId, String sessionId);

    List<ChatSessionRecord> findByUserIdOrderByUpdatedAtDesc(Long userId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}
