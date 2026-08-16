package com.example.vatica.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRecordRepository extends JpaRepository<ChatSessionRecord, Long> {

    Optional<ChatSessionRecord> findByUserIdAndSessionId(Long userId, String sessionId);

    List<ChatSessionRecord> findByUserIdOrderByUpdatedAtDesc(Long userId);

    void deleteByUserIdAndSessionId(Long userId, String sessionId);
}
