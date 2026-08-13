package com.example.vatica.controller;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 会话短期记忆（内存版，迭代 2.5 I2.5-3，代码审查 R2 修复）。
 *
 * <p>多轮对话上下文：每轮只存 user 与最终 assistant 纯文本（工具调用的中间过程不落库）；
 * 单会话滑动窗口双上限——消息数（{@code maxMessages}）与字符数（{@code maxChars}，
 * token 的工程近似，中文约 1 字 ≈ 1 token）——防上下文膨胀；会话总数上限
 * （{@code maxSessions}）按 LRU 淘汰防内存无限增长。不持久化——持久化与摘要压缩仍在迭代 5。
 *
 * <p>纯 POJO，不依赖 Spring，单测可直接 {@code new}；synchronized 保证并发安全
 * （虚拟线程场景多请求并发写同一会话也不丢消息）。
 */
public final class SessionMemory {

    private final int maxMessages;
    private final int maxChars;

    /** accessOrder=true：get/append 视为访问，removeEldestEntry 淘汰最久未用会话。 */
    private final Map<String, Bucket> sessions;

    /** 单会话消息队列 + 字符总数（增量维护，避免每次 O(n) 重算）。 */
    private static final class Bucket {
        final ArrayDeque<Message> messages = new ArrayDeque<>();
        int chars = 0;
    }

    public SessionMemory(int maxMessages, int maxSessions, int maxChars) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages 必须为正数");
        }
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions 必须为正数");
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须为正数");
        }
        this.maxMessages = maxMessages;
        this.maxChars = maxChars;
        this.sessions = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > maxSessions;
            }
        };
    }

    /** 取历史消息快照（时间正序）；无会话记录时返回空列表。 */
    public synchronized List<Message> history(String sessionId) {
        Bucket bucket = sessions.get(keyOf(sessionId));
        return bucket == null ? List.of() : List.copyOf(bucket.messages);
    }

    /** 记录一轮对话（user + assistant）；空文本不记录（DeepSeek v4 思考模式下内容可能为空）。 */
    public synchronized void append(String sessionId, String userText, String assistantText) {
        Bucket bucket = sessions.computeIfAbsent(keyOf(sessionId), k -> new Bucket());
        add(bucket, new UserMessage(userText));
        add(bucket, new AssistantMessage(assistantText));
        trim(bucket);
    }

    private void add(Bucket bucket, Message message) {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        bucket.messages.addLast(message);
        bucket.chars += text.length();
    }

    /** 双上限裁剪：消息数超限丢最旧；字符数超限继续丢最旧（至少保留最新一条，避免截断当前消息）。 */
    private void trim(Bucket bucket) {
        while (bucket.messages.size() > maxMessages) {
            bucket.chars -= bucket.messages.removeFirst().getText().length();
        }
        while (bucket.chars > maxChars && bucket.messages.size() > 1) {
            bucket.chars -= bucket.messages.removeFirst().getText().length();
        }
    }

    /** 当前活跃会话数（可观测性 / 单测）。 */
    public synchronized int sessionCount() {
        return sessions.size();
    }

    private static String keyOf(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
    }
}
