package com.example.vatica.controller;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 会话消息持久化实体（迭代 5 I5-4）：短期记忆持久化（重启不丢）。
 *
 * <p>只存 user / 最终 assistant 纯文本（工具调用中间过程不落库——迭代 2.5 的
 * 既定结论：避免 dangling tool-call 消息污染下一轮上下文）。
 */
@Entity
@Table(name = "vatica_chat_message", indexes = {
        @Index(name = "idx_msg_owner_session_seq", columnList = "userId,sessionId,seq"),
        @Index(name = "idx_msg_tenant_session_seq", columnList = "orgId,userId,sessionId,seq") })
public class ChatMessageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long userId;

    @Column(updatable = false)
    private Long orgId;

    @Column(nullable = false, length = 64)
    private String sessionId;

    /** USER / ASSISTANT。 */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 会话内单调序号（排序用）。 */
    @Column(nullable = false)
    private long seq;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ChatMessageRecord() {
        // JPA
    }

    public ChatMessageRecord(Long userId, Long orgId, String sessionId, String role, String content, long seq) {
        this.userId = userId;
        this.orgId = orgId;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.seq = seq;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getSeq() {
        return seq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
