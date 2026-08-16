package com.example.vatica.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 14：用户会话元数据与消息历史的跨设备同步接口。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatSessionRecordRepository sessions;
    private final ChatMessageRecordRepository messages;

    public SessionController(ChatSessionRecordRepository sessions, ChatMessageRecordRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    public record SessionUpsertRequest(String title) { }
    public record SessionSummary(String id, String title, Instant createdAt, Instant updatedAt) { }
    public record SessionMessage(String role, String content, Instant createdAt) { }
    public record SessionDetail(String id, String title, Instant createdAt, Instant updatedAt,
            List<SessionMessage> messages) { }

    @GetMapping
    public List<SessionSummary> list() {
        RequestIdentity identity = RequestIdentityContext.require();
        return sessions.findByUserIdOrderByUpdatedAtDesc(identity.userId()).stream()
                .map(this::summary)
                .toList();
    }

    @GetMapping("/{sessionId}")
    public SessionDetail get(@PathVariable String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        ChatSessionRecord session = owned(identity, sessionId);
        List<SessionMessage> history = messages
                .findByUserIdAndSessionIdOrderBySeqAsc(identity.userId(), sessionId).stream()
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
        ChatSessionRecord session = sessions.findByUserIdAndSessionId(identity.userId(), id)
                .orElseGet(() -> new ChatSessionRecord(identity.userId(), identity.orgId(), id, finalTitle));
        session.setTitle(finalTitle);
        return summary(sessions.save(session));
    }

    @DeleteMapping("/{sessionId}")
    @Transactional
    public void delete(@PathVariable String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String id = normalizeId(sessionId);
        owned(identity, id);
        messages.deleteByUserIdAndSessionId(identity.userId(), id);
        sessions.deleteByUserIdAndSessionId(identity.userId(), id);
    }

    private ChatSessionRecord owned(RequestIdentity identity, String sessionId) {
        return sessions.findByUserIdAndSessionId(identity.userId(), normalizeId(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("操作失败：会话不存在。"));
    }

    private SessionSummary summary(ChatSessionRecord record) {
        return new SessionSummary(record.getSessionId(), record.getTitle(), record.getCreatedAt(),
                record.getUpdatedAt());
    }

    private static String normalizeId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
            throw new IllegalArgumentException("操作失败：会话 ID 不合法。");
        }
        return sessionId.trim();
    }
}
