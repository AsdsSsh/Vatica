package com.example.vatica.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.model.ConversationMessage;

/**
 * 会话短期记忆·MySQL 持久化实现（迭代 5 I5-4；迭代 15 I15-9 增加中期摘要层）。
 *
 * <p>架构（面试可讲）：<b>内存滑窗做热缓存 + MySQL 落库</b>——
 * <ul>
 *   <li>写入：append 同时更新内存窗口与数据库（每轮 2 行，user + 最终 assistant）</li>
 *   <li>读取：缓存命中直接返回；未命中（应用重启后）从库中取最近 N 条重建滑窗</li>
 *   <li>迭代 15：滑窗溢出的旧消息由 {@link SessionSummaryService} 异步压缩为
 *       vatica_chat_session.summary_text；history 只回近期原文，summary 由 ContextAssembler 拼接</li>
 * </ul>
 */
public final class JpaSessionMemory implements SessionMemory {

    private static final int FALLBACK_HEAD_MESSAGES = 2;
    private static final int FALLBACK_TAIL_MESSAGES = 2;

    private final InMemorySessionMemory cache;
    private final ChatMessageRecordRepository repository;
    private final int windowSize;
    private final ChatSessionRecordRepository sessions;
    private final SessionSummaryService summaryService;

    public JpaSessionMemory(InMemorySessionMemory cache, ChatMessageRecordRepository repository, int windowSize) {
        this(cache, repository, windowSize, null, null);
    }

    public JpaSessionMemory(InMemorySessionMemory cache, ChatMessageRecordRepository repository, int windowSize,
            ChatSessionRecordRepository sessions, SessionSummaryService summaryService) {
        this.cache = cache;
        this.repository = repository;
        this.windowSize = windowSize;
        this.sessions = sessions;
        this.summaryService = summaryService;
    }

    @Override
    public synchronized List<ConversationMessage> history(String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        String key = cacheKey(identity.userId(), session);
        if (!cache.contains(key)) {
            restore(identity.userId(), session, key);
        }
        return cache.history(key);
    }

    @Override
    public synchronized void append(String sessionId, String userText, String assistantText) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        String key = cacheKey(identity.userId(), session);
        if (!cache.contains(key)) {
            restore(identity.userId(), session, key);
        }
        cache.append(key, userText, assistantText);

        long nextSeq = repository.findByUserIdAndSessionIdOrderBySeqDesc(identity.userId(), session,
                PageRequest.of(0, 1))
                .stream().findFirst().map(r -> r.getSeq() + 1).orElse(1L);
        List<ChatMessageRecord> rows = new ArrayList<>(2);
        if (userText != null && !userText.isBlank()) {
            rows.add(new ChatMessageRecord(identity.userId(), identity.orgId(), session,
                    "USER", userText, nextSeq++));
        }
        if (assistantText != null && !assistantText.isBlank()) {
            rows.add(new ChatMessageRecord(identity.userId(), identity.orgId(), session,
                    "ASSISTANT", assistantText, nextSeq++));
        }
        repository.saveAll(rows);
        // 迭代 15 I15-9：窗口溢出后异步触发中期摘要（targetSeq=最新 seq - 窗口大小）
        if (summaryService != null && sessions != null && !rows.isEmpty()) {
            long lastWrittenSeq = rows.getLast().getSeq();
            long targetSeq = Math.max(0, lastWrittenSeq - windowSize);
            summaryService.schedule(identity.userId(), identity.orgId(), session, targetSeq);
        }
    }

    @Override
    public synchronized String summary(String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        if (sessions == null) {
            return null;
        }
        return sessions.findByUserIdAndSessionId(identity.userId(), session)
                .map(ChatSessionRecord::getSummaryText)
                .orElse(null);
    }

    @Override
    public synchronized List<ConversationMessage> recent(String sessionId) {
        return history(sessionId);
    }

    /**
     * 迭代 29A：摘要失败不等于把较早历史直接从模型上下文中抹去。
     * 近期滑窗以外的未摘要区间才读取头尾片段，避免为 Prompt 全量读取历史。
     */
    @Override
    public synchronized ContextWindow contextWindow(String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        List<ChatMessageRecord> recentRows = repository.findByUserIdAndSessionIdOrderBySeqDesc(
                identity.userId(), session, PageRequest.of(0, windowSize));
        recentRows = new ArrayList<>(recentRows);
        Collections.reverse(recentRows);
        List<ConversationMessage> recent = recentRows.stream().map(this::message).toList();
        if (sessions == null) {
            return new ContextWindow(null, SessionSummaryStatus.PENDING, 0,
                    0, recent.size(), List.of(), List.of(), recent);
        }

        ChatSessionRecord record = sessions.findByUserIdAndSessionId(identity.userId(), session).orElse(null);
        String summary = record == null ? null : record.getSummaryText();
        SessionSummaryStatus status = record == null
                ? SessionSummaryStatus.PENDING : record.getSummaryStatus();
        long through = record == null ? 0 : record.getSummaryThroughSeq();
        long requested = record == null ? 0 : record.getSummaryRequestedThroughSeq();
        long uncovered = repository.countByUserIdAndSessionIdAndSeqGreaterThan(identity.userId(), session, through);
        List<ConversationMessage> head = List.of();
        List<ConversationMessage> tail = List.of();
        if (!recentRows.isEmpty()) {
            long recentStartSeq = recentRows.getFirst().getSeq();
            long gapCount = repository.countByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThan(
                    identity.userId(), session, through, recentStartSeq);
            if (gapCount > 0) {
                List<ChatMessageRecord> headRows = repository
                        .findByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqAsc(
                                identity.userId(), session, through, recentStartSeq,
                                PageRequest.of(0, FALLBACK_HEAD_MESSAGES));
                List<ChatMessageRecord> tailRows = repository
                        .findByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqDesc(
                                identity.userId(), session, through, recentStartSeq,
                                PageRequest.of(0, FALLBACK_TAIL_MESSAGES));
                Collections.reverse(tailRows);
                Set<Long> headSeqs = new HashSet<>();
                headRows.forEach(row -> headSeqs.add(row.getSeq()));
                head = headRows.stream().map(this::message).toList();
                tail = tailRows.stream().filter(row -> !headSeqs.contains(row.getSeq()))
                        .map(this::message).toList();
            }
        }
        return new ContextWindow(summary, status, through, requested, uncovered, head, tail, recent);
    }

    /** 从库中取最近 windowSize 条重建滑窗（重启恢复路径）。 */
    private void restore(Long userId, String sessionId, String key) {
        List<ChatMessageRecord> recent = repository.findByUserIdAndSessionIdOrderBySeqDesc(userId, sessionId,
                PageRequest.of(0, windowSize));
        List<ConversationMessage> messages = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessageRecord r = recent.get(i);
            messages.add("USER".equals(r.getRole())
                    ? ConversationMessage.user(r.getContent())
                    : ConversationMessage.assistant(r.getContent()));
        }
        cache.restore(key, messages);
    }

    private ConversationMessage message(ChatMessageRecord record) {
        return "USER".equals(record.getRole())
                ? ConversationMessage.user(record.getContent())
                : ConversationMessage.assistant(record.getContent());
    }

    private static String sessionIdOf(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
    }

    private static String cacheKey(Long userId, String sessionId) {
        return "user:" + userId + ":session:" + sessionId;
    }
}
