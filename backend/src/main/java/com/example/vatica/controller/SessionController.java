package com.example.vatica.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.context.ContextFactScopeType;
import com.example.vatica.context.ContextFactService;

/** 迭代 14：用户会话元数据与消息历史的跨设备同步接口。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatSessionRecordRepository sessions;
    private final ChatMessageRecordRepository messages;
    private final ChatSummarySegmentRecordRepository summarySegments;
    private final ContextFactService contextFacts;
    private final SessionMemory sessionMemory;

    @Autowired
    public SessionController(ChatSessionRecordRepository sessions, ChatMessageRecordRepository messages,
            ChatSummarySegmentRecordRepository summarySegments, ContextFactService contextFacts,
            SessionMemory sessionMemory) {
        this.sessions = sessions;
        this.messages = messages;
        this.summarySegments = summarySegments;
        this.contextFacts = contextFacts;
        this.sessionMemory = sessionMemory;
    }

    public SessionController(ChatSessionRecordRepository sessions, ChatMessageRecordRepository messages,
            ContextFactService contextFacts) {
        this(sessions, messages, null, contextFacts, null);
    }

    /** 兼容 14A 测试/嵌入式调用方；没有事实层时仍可管理会话。 */
    public SessionController(ChatSessionRecordRepository sessions, ChatMessageRecordRepository messages) {
        this(sessions, messages, null, null, null);
    }

    public record SessionUpsertRequest(String title) { }
    public record SessionSummary(String id, String title, Instant createdAt, Instant updatedAt) { }
    public record SessionMessage(String role, String content, Instant createdAt) { }
    public record SessionDetail(String id, String title, Instant createdAt, Instant updatedAt,
            List<SessionMessage> messages) { }

    @GetMapping
    public List<SessionSummary> list() {
        RequestIdentity identity = RequestIdentityContext.require();
        return sessions.findByOrgIdAndUserIdOrderByUpdatedAtDesc(identity.orgId(), identity.userId()).stream()
                .map(this::summary)
                .toList();
    }

    @GetMapping("/{sessionId}")
    public SessionDetail get(@PathVariable String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        ChatSessionRecord session = owned(identity, sessionId);
        List<SessionMessage> history = messages
                .findByOrgIdAndUserIdAndSessionIdOrderBySeqAsc(
                        identity.orgId(), identity.userId(), session.getSessionId()).stream()
                .map(row -> new SessionMessage(row.getRole(), row.getContent(), row.getCreatedAt()))
                .toList();
        return new SessionDetail(session.getSessionId(), session.getTitle(), session.getCreatedAt(),
                session.getUpdatedAt(), history);
    }

    @PutMapping("/{sessionId}")
    @Transactional
    public SessionSummary upsert(@PathVariable String sessionId, @RequestBody SessionUpsertRequest body) {
        RequestIdentity identity = RequestIdentityContext.require();
        String id = normalizeId(sessionId);
        String title = body == null || body.title() == null || body.title().isBlank()
                ? "新会话" : body.title().trim();
        if (title.length() > 80) {
            title = title.substring(0, 80);
        }
        String finalTitle = title;
        ChatSessionRecord session = sessions.findByUserIdAndOrgIdAndSessionId(identity.userId(), identity.orgId(), id)
                .orElseGet(() -> new ChatSessionRecord(identity.userId(), identity.orgId(), id, finalTitle));
        session.setTitle(finalTitle);
        return summary(sessions.save(session));
    }

    @DeleteMapping("/{sessionId}")
    @Transactional
    public void delete(@PathVariable String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String id = normalizeId(sessionId);
        sessions.findForUpdate(identity.userId(), identity.orgId(), id)
                .orElseThrow(() -> new IllegalArgumentException("操作失败：会话不存在。"));
        messages.deleteByOrgIdAndUserIdAndSessionId(identity.orgId(), identity.userId(), id);
        if (summarySegments != null) {
            summarySegments.deleteByOrgIdAndUserIdAndSessionId(identity.orgId(), identity.userId(), id);
        }
        if (contextFacts != null) {
            contextFacts.deleteScope(ContextFactScopeType.CHAT_SESSION, id);
        }
        sessions.deleteByOrgIdAndUserIdAndSessionId(identity.orgId(), identity.userId(), id);
        evictAfterCommit(id);
    }

    private ChatSessionRecord owned(RequestIdentity identity, String sessionId) {
        return sessions.findByUserIdAndOrgIdAndSessionId(identity.userId(), identity.orgId(), normalizeId(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("操作失败：会话不存在。"));
    }

    private SessionSummary summary(ChatSessionRecord record) {
        return new SessionSummary(record.getSessionId(), record.getTitle(), record.getCreatedAt(),
                record.getUpdatedAt());
    }

    private void evictAfterCommit(String sessionId) {
        if (sessionMemory == null) {
            return;
        }
        Runnable evict = () -> sessionMemory.evict(sessionId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }

    private static String normalizeId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new IllegalArgumentException("操作失败：会话 ID 不合法。");
        }
        return sessionId.trim();
    }
}
