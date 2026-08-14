package com.example.vatica.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** 会话消息仓库（迭代 5 I5-4）。 */
public interface ChatMessageRecordRepository extends JpaRepository<ChatMessageRecord, Long> {

    /** 按 seq 倒序取最近 N 条（Pageable 由调用方给 limit）。 */
    List<ChatMessageRecord> findBySessionIdOrderBySeqDesc(String sessionId, Pageable pageable);
}
