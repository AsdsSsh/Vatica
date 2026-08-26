package com.example.vatica.controller;

import java.util.List;

import com.example.vatica.model.ConversationMessage;

/**
 * 会话短期记忆抽象（迭代 5）：多轮对话上下文存取。
 *
 * <p>实现：{@link InMemorySessionMemory}（内存版，滑动窗口双上限，单测友好）；
 * {@link JpaSessionMemory}（迭代 5 持久化：内存窗口做热缓存、数据库落盘，重启不丢）。
 */
public interface SessionMemory {

    /** 取历史消息快照（时间正序）；无记录返回空列表。 */
    List<ConversationMessage> history(String sessionId);

    /** 记录一轮对话（user + assistant 纯文本）。 */
    void append(String sessionId, String userText, String assistantText);

    /** 删除会话后驱逐请求身份范围内的热缓存；无缓存实现保持 no-op。 */
    default void evict(String sessionId) {
    }

    /** 迭代 15 I15-9：中期滚动摘要（无摘要实现返回 null）。 */
    default String summary(String sessionId) {
        return null;
    }

    /** 迭代 15 I15-9：水位线之后的近期原文（默认与 history 相同）。 */
    default List<ConversationMessage> recent(String sessionId) {
        return history(sessionId);
    }

    /**
     * 迭代 29A：上下文组装使用的内存视图。
     *
     * <p>默认实现保持内存版/测试替身兼容。JPA 实现会在摘要落后时附加未摘要历史的
     * 头部片段，近期消息本身承担尾部片段，避免将完整原文回灌给模型。</p>
     */
    default ContextWindow contextWindow(String sessionId) {
        List<ConversationMessage> recent = recent(sessionId);
        return new ContextWindow(summary(sessionId), SessionSummaryStatus.PENDING, 0, 0,
                recent.size(), List.of(), List.of(), recent);
    }

    /**
     * 迭代 31B：调用方可以按本次模型预算扩大数据库原文读取，而不扩大 JVM 热缓存。
     * 默认实现保持内存版和旧测试替身兼容。
     */
    default ContextWindow contextWindow(String sessionId, SessionContextReadRequest request) {
        return contextWindow(sessionId);
    }

    record ContextWindow(String summary, SessionSummaryStatus summaryStatus, long summaryThroughSeq,
            long summaryRequestedThroughSeq, long uncoveredMessageCount,
            List<ConversationMessage> uncoveredHead, List<ConversationMessage> uncoveredTail,
            List<ConversationMessage> recent, long recentStartSeq, long recentEndSeq) {

        /** 兼容迭代 29 的调用方；无序号视图不能用于按需证据去重。 */
        public ContextWindow(String summary, SessionSummaryStatus summaryStatus, long summaryThroughSeq,
                long summaryRequestedThroughSeq, long uncoveredMessageCount,
                List<ConversationMessage> uncoveredHead, List<ConversationMessage> uncoveredTail,
                List<ConversationMessage> recent) {
            this(summary, summaryStatus, summaryThroughSeq, summaryRequestedThroughSeq, uncoveredMessageCount,
                    uncoveredHead, uncoveredTail, recent, 0, 0);
        }

        public ContextWindow {
            summaryStatus = summaryStatus == null ? SessionSummaryStatus.PENDING : summaryStatus;
            summaryThroughSeq = Math.max(0, summaryThroughSeq);
            summaryRequestedThroughSeq = Math.max(0, summaryRequestedThroughSeq);
            uncoveredMessageCount = Math.max(0, uncoveredMessageCount);
            uncoveredHead = uncoveredHead == null ? List.of() : List.copyOf(uncoveredHead);
            uncoveredTail = uncoveredTail == null ? List.of() : List.copyOf(uncoveredTail);
            recent = recent == null ? List.of() : List.copyOf(recent);
            recentStartSeq = Math.max(0, recentStartSeq);
            recentEndSeq = Math.max(0, recentEndSeq);
        }

        public boolean hasFallbackHistory() {
            return !uncoveredHead.isEmpty() || !uncoveredTail.isEmpty();
        }
    }
}
