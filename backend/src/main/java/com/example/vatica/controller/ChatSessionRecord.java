package com.example.vatica.controller;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 迭代 14：跨设备同步的用户会话元数据。 */
@Entity
@Table(name = "vatica_chat_session",
        uniqueConstraints = @UniqueConstraint(name = "uk_session_owner_id", columnNames = { "userId", "sessionId" }),
        indexes = @Index(name = "idx_session_owner_updated", columnList = "userId,updatedAt"))
public class ChatSessionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ChatSessionRecord() {
    }

    public ChatSessionRecord(Long userId, Long orgId, String sessionId, String title) {
        this.userId = userId;
        this.orgId = orgId;
        this.sessionId = sessionId;
        this.title = title;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setTitle(String title) { this.title = title; }
}
