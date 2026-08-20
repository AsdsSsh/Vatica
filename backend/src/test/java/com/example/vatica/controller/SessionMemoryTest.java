package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import com.example.vatica.model.ConversationMessage;

/** 会话短期记忆单测（迭代 2.5 I2.5-3）：滑动窗口、空文本跳过、默认会话、LRU 淘汰。 */
class SessionMemoryTest {

    /** 无记录的会话返回空历史 */
    @Test
    void historyEmptyByDefault() {
        InMemorySessionMemory memory = new InMemorySessionMemory(20, 4, 10000);
        assertThat(memory.history("s1")).isEmpty();
    }

    /** 一轮对话按 user → assistant 顺序入记忆 */
    @Test
    void appendsAndReturnsHistoryInOrder() {
        InMemorySessionMemory memory = new InMemorySessionMemory(20, 4, 10000);
        memory.append("s1", "今天天气怎么样", "晴天，25 度");
        memory.append("s1", "适合跑步吗", "适合，注意补水");

        List<ConversationMessage> history = memory.history("s1");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(history.get(1).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(history.get(2).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(history.get(3).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(history.get(2).text()).isEqualTo("适合跑步吗");
    }

    /** 空文本不记录（DeepSeek v4 思考模式下 assistant 内容可能为空） */
    @Test
    void blankTextsAreSkipped() {
        InMemorySessionMemory memory = new InMemorySessionMemory(20, 4, 10000);
        memory.append("s1", "你好", "");
        memory.append("s1", "   ", "回复");

        List<ConversationMessage> history = memory.history("s1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(history.get(1).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
    }

    /** null / 空白 sessionId 归入同一个默认会话 */
    @Test
    void nullAndBlankSessionIdShareDefaultSession() {
        InMemorySessionMemory memory = new InMemorySessionMemory(20, 4, 10000);
        memory.append(null, "A", "a");
        memory.append("", "B", "b");
        memory.append("   ", "C", "c");

        assertThat(memory.sessionCount()).isEqualTo(1);
        assertThat(memory.history(null)).hasSize(6);
    }

    /** 滑动窗口：超过 maxMessages 时丢弃最旧消息 */
    @Test
    void trimsToMaxMessages() {
        InMemorySessionMemory memory = new InMemorySessionMemory(4, 4, 10000); // 最多 2 轮
        memory.append("s1", "第一轮问", "第一轮答");
        memory.append("s1", "第二轮问", "第二轮答");
        memory.append("s1", "第三轮问", "第三轮答");

        List<ConversationMessage> history = memory.history("s1");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).text()).isEqualTo("第二轮问");
        assertThat(history.get(3).text()).isEqualTo("第三轮答");
    }

    /** 会话数超上限时按 LRU 淘汰最久未用会话（访问会刷新热度） */
    @Test
    void evictsLeastRecentlyUsedSession() {
        InMemorySessionMemory memory = new InMemorySessionMemory(20, 2, 10000);
        memory.append("s1", "A", "a");
        memory.append("s2", "B", "b");
        memory.history("s1");          // 刷新 s1 热度
        memory.append("s3", "C", "c"); // 触发淘汰：s2 最久未用

        assertThat(memory.sessionCount()).isEqualTo(2);
        assertThat(memory.history("s2")).isEmpty();
        assertThat(memory.history("s1")).isNotEmpty();
        assertThat(memory.history("s3")).isNotEmpty();
    }

    /** 字符数上限：历史总字符数超限时丢最旧消息（丢到 ≤ 上限为止，至少保留最新一条） */
    @Test
    void trimsByMaxChars() {
        InMemorySessionMemory memory = new InMemorySessionMemory(100, 4, 10); // 上限 10 字符
        memory.append("s1", "一二三四五六", "回一");  // 6+2=8 字符
        memory.append("s1", "七八九十", "回二");      // 8+4+2=14 > 10 → 丢最旧 U1(6) → 8 ≤ 10 停

        List<ConversationMessage> history = memory.history("s1");
        assertThat(history).hasSize(3); // [回一, 七八九十, 回二]
        assertThat(history.get(0).text()).isEqualTo("回一");
        assertThat(history.get(1).text()).isEqualTo("七八九十");
        assertThat(history.get(2).text()).isEqualTo("回二");
    }

    /** 单条消息超字符上限也不截断（保留最新完整消息，仅丢更旧的） */
    @Test
    void singleOversizeMessageIsKeptWhole() {
        InMemorySessionMemory memory = new InMemorySessionMemory(100, 4, 5);
        memory.append("s1", "一二三四五六七八九十", ""); // 10 字符 > 上限 5

        List<ConversationMessage> history = memory.history("s1");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).text()).isEqualTo("一二三四五六七八九十");
    }

    /** 非法配置拒绝：maxMessages / maxSessions / maxChars 必须为正数 */
    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new InMemorySessionMemory(0, 4, 10000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemorySessionMemory(20, -1, 10000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemorySessionMemory(20, 4, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
