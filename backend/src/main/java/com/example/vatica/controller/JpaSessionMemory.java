package com.example.vatica.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.context.TokenEstimator;
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
    private static final int DEFAULT_LONG_CONTEXT_MAX_MESSAGES = 512;

    private final InMemorySessionMemory cache;
    private final ChatMessageRecordRepository repository;
    private final int windowSize;
    private final ChatSessionRecordRepository sessions;
    private final SessionSummaryService summaryService;
    private final int longContextMaxMessages;

    public JpaSessionMemory(InMemorySessionMemory cache, ChatMessageRecordRepository repository, int windowSize) {
        this(cache, repository, windowSize, null, null, DEFAULT_LONG_CONTEXT_MAX_MESSAGES);
    }

    public JpaSessionMemory(InMemorySessionMemory cache, ChatMessageRecordRepository repository, int windowSize,
            ChatSessionRecordRepository sessions, SessionSummaryService summaryService) {
        this(cache, repository, windowSize, sessions, summaryService, DEFAULT_LONG_CONTEXT_MAX_MESSAGES);
    }

    public JpaSessionMemory(InMemorySessionMemory cache, ChatMessageRecordRepository repository, int windowSize,
            ChatSessionRecordRepository sessions, SessionSummaryService summaryService, int longContextMaxMessages) {
        this.cache = cache;
        this.repository = repository;
        this.windowSize = Math.max(1, windowSize);
        this.sessions = sessions;
        this.summaryService = summaryService;
        this.longContextMaxMessages = Math.max(this.windowSize, longContextMaxMessages);
    }

    @Override
    public synchronized List<ConversationMessage> history(String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        String key = cacheKey(identity.orgId(), identity.userId(), session);
        if (!cache.contains(key)) {
            restore(identity, session, key);
        }
        return cache.history(key);
    }

    @Override
    public synchronized void append(String sessionId, String userText, String assistantText) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        String key = cacheKey(identity.orgId(), identity.userId(), session);
        if (!cache.contains(key)) {
            restore(identity, session, key);
        }
        cache.append(key, userText, assistantText);

        long nextSeq = repository.findByOrgIdAndUserIdAndSessionIdOrderBySeqDesc(identity.orgId(), identity.userId(), session,
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
        return sessions.findByUserIdAndOrgIdAndSessionId(identity.userId(), identity.orgId(), session)
                .map(ChatSessionRecord::getSummaryText)
                .orElse(null);
    }

    @Override
    public synchronized List<ConversationMessage> recent(String sessionId) {
        return history(sessionId);
    }

    @Override
    public synchronized void evict(String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        cache.evict(cacheKey(identity.orgId(), identity.userId(), session));
        if (summaryService != null) {
            summaryService.cancel(identity.userId(), identity.orgId(), session);
        }
    }

    /**
     * 迭代 29A：摘要失败不等于把较早历史直接从模型上下文中抹去。
     * 近期滑窗以外的未摘要区间才读取头尾片段，避免为 Prompt 全量读取历史。
     */
    @Override
    public synchronized ContextWindow contextWindow(String sessionId) {
        return contextWindow(sessionId, SessionContextReadRequest.hotWindow(windowSize));
    }

    /**
     * 数据库故障时的纯热缓存视图；绝不尝试 restore 或读取摘要/持久层。
     */
    @Override
    public synchronized ContextWindow hotContextWindow(String sessionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        String key = cacheKey(identity.orgId(), identity.userId(), session);
        if (!cache.contains(key)) {
            return new ContextWindow(null, SessionSummaryStatus.PENDING, 0, 0,
                    0, List.of(), List.of(), List.of(), 0, 0);
        }
        List<ConversationMessage> recent = cache.history(key);
        return new ContextWindow(null, SessionSummaryStatus.PENDING, 0, 0,
                recent.size(), List.of(), List.of(), recent, 0, 0);
    }

    /**
     * 迭代 31B：近期原文按本次模型配额回源；JVM 缓存依然只有 {@code windowSize} 条。
     * 大窗口不能绕过行数和 token 两道硬上限，也不读取同用户的其他组织数据。
     */
    @Override
    public synchronized ContextWindow contextWindow(String sessionId, SessionContextReadRequest request) {
        RequestIdentity identity = RequestIdentityContext.require();
        String session = sessionIdOf(sessionId);
        SessionContextReadRequest read = request == null
                ? SessionContextReadRequest.hotWindow(windowSize) : request;
        int readLimit = read.hasTokenBudget()
                ? Math.min(longContextMaxMessages, read.maxMessages()) : windowSize;
        List<ChatMessageRecord> recentRows = read.hasTokenBudget() && read.recentTokenBudget() == 0
                ? List.of() : repository.findByOrgIdAndUserIdAndSessionIdOrderBySeqDesc(
                        identity.orgId(), identity.userId(), session, PageRequest.of(0, readLimit));
        recentRows = new ArrayList<>(recentRows);
        Collections.reverse(recentRows);
        if (read.hasTokenBudget()) {
            recentRows = recentRowsWithinBudget(recentRows, read.recentTokenBudget());
        }
        List<ConversationMessage> recent = recentRows.stream().map(this::message).toList();
        long recentStartSeq = recentRows.isEmpty() ? 0 : recentRows.getFirst().getSeq();
        long recentEndSeq = recentRows.isEmpty() ? 0 : recentRows.getLast().getSeq();
        if (sessions == null) {
            return new ContextWindow(null, SessionSummaryStatus.PENDING, 0,
                    0, recent.size(), List.of(), List.of(), recent, recentStartSeq, recentEndSeq);
        }

        ChatSessionRecord record = sessions.findByUserIdAndOrgIdAndSessionId(
                identity.userId(), identity.orgId(), session).orElse(null);
        String summary = record == null ? null : record.getSummaryText();
        SessionSummaryStatus status = record == null
                ? SessionSummaryStatus.PENDING : record.getSummaryStatus();
        long through = record == null ? 0 : record.getSummaryThroughSeq();
        long requested = record == null ? 0 : record.getSummaryRequestedThroughSeq();
        long uncovered = repository.countByOrgIdAndUserIdAndSessionIdAndSeqGreaterThan(
                identity.orgId(), identity.userId(), session, through);
        List<ConversationMessage> head = List.of();
        List<ConversationMessage> tail = List.of();
        if (!recentRows.isEmpty()) {
            long gapCount = repository.countByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThan(
                    identity.orgId(), identity.userId(), session, through, recentStartSeq);
            if (gapCount > 0) {
                List<ChatMessageRecord> headRows = repository
                        .findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqAsc(
                                identity.orgId(), identity.userId(), session, through, recentStartSeq,
                                PageRequest.of(0, FALLBACK_HEAD_MESSAGES));
                List<ChatMessageRecord> tailRows = repository
                        .findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanOrderBySeqDesc(
                                identity.orgId(), identity.userId(), session, through, recentStartSeq,
                                PageRequest.of(0, FALLBACK_TAIL_MESSAGES));
                Collections.reverse(tailRows);
                Set<Long> headSeqs = new HashSet<>();
                headRows.forEach(row -> headSeqs.add(row.getSeq()));
                head = headRows.stream().map(this::message).toList();
                tail = tailRows.stream().filter(row -> !headSeqs.contains(row.getSeq()))
                        .map(this::message).toList();
            }
        }
        return new ContextWindow(summary, status, through, requested, uncovered, head, tail, recent,
                recentStartSeq, recentEndSeq);
    }

    /** 从库中取最近 windowSize 条重建滑窗（重启恢复路径）。 */
    private void restore(RequestIdentity identity, String sessionId, String key) {
        List<ChatMessageRecord> recent = repository.findByOrgIdAndUserIdAndSessionIdOrderBySeqDesc(
                identity.orgId(), identity.userId(), sessionId, PageRequest.of(0, windowSize));
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

    /** 按完整 USER→ASSISTANT 轮次保留尾部；孤立回答和超预算整轮都不回灌。 */
    private static List<ChatMessageRecord> recentRowsWithinBudget(List<ChatMessageRecord> rows, int tokenBudget) {
        if (rows.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }
        List<List<ChatMessageRecord>> turns = new ArrayList<>();
        List<ChatMessageRecord> current = null;
        for (ChatMessageRecord row : rows) {
            if ("USER".equals(row.getRole())) {
                if (current != null && !current.isEmpty()) {
                    turns.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                current.add(row);
            } else if ("ASSISTANT".equals(row.getRole()) && current != null) {
                current.add(row);
            }
        }
        if (current != null && !current.isEmpty()) {
            turns.add(List.copyOf(current));
        }

        List<List<ChatMessageRecord>> selectedTurns = new ArrayList<>();
        int used = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            List<ChatMessageRecord> turn = turns.get(i);
            int turnTokens = turn.stream()
                    .mapToInt(row -> Math.max(1, TokenEstimator.estimate(row.getContent())))
                    .sum();
            if ((long) used + turnTokens > tokenBudget) {
                break;
            }
            selectedTurns.add(turn);
            used += turnTokens;
        }
        Collections.reverse(selectedTurns);
        List<ChatMessageRecord> selected = selectedTurns.stream().flatMap(List::stream).toList();
        return List.copyOf(selected);
    }

    private static String sessionIdOf(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
    }

    private static String cacheKey(Long orgId, Long userId, String sessionId) {
        return "org:" + orgId + ":user:" + userId + ":session:" + sessionId;
    }
}
