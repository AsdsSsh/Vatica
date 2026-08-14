package com.example.vatica.controller;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * 会话短期记忆抽象（迭代 5）：多轮对话上下文存取。
 *
 * <p>实现：{@link InMemorySessionMemory}（内存版，滑动窗口双上限，单测友好）；
 * {@link JpaSessionMemory}（迭代 5 持久化：内存窗口做热缓存、MySQL 落库，重启不丢）。
 */
public interface SessionMemory {

    /** 取历史消息快照（时间正序）；无记录返回空列表。 */
    List<Message> history(String sessionId);

    /** 记录一轮对话（user + assistant 纯文本）。 */
    void append(String sessionId, String userText, String assistantText);
}
