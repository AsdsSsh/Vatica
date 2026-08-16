package com.example.vatica.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/**
 * 会话短期记忆·MySQL 持久化实现（迭代 5 I5-4）。
 *
 * <p>架构（面试可讲）：<b>内存滑窗做热缓存 + MySQL 落库</b>——
 * <ul>
 *   <li>写入：append 同时更新内存窗口与数据库（每轮 2 行，user + 最终 assistant）</li>
 *   <li>读取：缓存命中直接返回；未命中（应用重启后）从库中取最近 N 条重建滑窗</li>
 *   <li>重启不丢：会话记忆从"内存版"升级为"短期持久化"；中期摘要压缩（Redis）仍按
 *       规划文档 7-05 的落地顺序留到后续迭代——不预置空层</li>
 * </ul>
 */
public final class JpaSessionMemory implements SessionMemory {

    private final InMemorySessionMemory cache;
    private final ChatMessageRecordRepository repository;
    private final int windowSize;

    public JpaSessionMemory(InMemorySessionMemory cache, ChatMessageRecordRepository repository, int windowSize) {
        this.cache = cache;
        this.repository = repository;
        this.windowSize = windowSize;
    }

    @Override
    public synchronized List<Message> history(String sessionId) {
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
                    "ASSISTANT", assistantText, nextSeq));
        }
        repository.saveAll(rows);
    }

    /** 从库中取最近 windowSize 条重建滑窗（重启恢复路径）。 */
    private void restore(Long userId, String sessionId, String key) {
        List<ChatMessageRecord> recent = repository.findByUserIdAndSessionIdOrderBySeqDesc(userId, sessionId,
                PageRequest.of(0, windowSize));
        List<Message> messages = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessageRecord r = recent.get(i);
            messages.add("USER".equals(r.getRole()) ? new UserMessage(r.getContent()) : new AssistantMessage(r.getContent()));
        }
        cache.restore(key, messages);
    }

    private static String sessionIdOf(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
    }

    private static String cacheKey(Long userId, String sessionId) {
        return "user:" + userId + ":session:" + sessionId;
    }
}
