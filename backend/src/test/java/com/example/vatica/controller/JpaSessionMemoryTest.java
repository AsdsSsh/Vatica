package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * 会话记忆持久化集成测试（迭代 5 I5-4）：H2 真库 + 新实例模拟"应用重启"，
 * 验证历史恢复、滑窗裁剪（重启恢复路径）、空文本不落库、多会话隔离。
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-msg;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class JpaSessionMemoryTest {

    @Autowired
    ChatMessageRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private JpaSessionMemory fresh() {
        return new JpaSessionMemory(new InMemorySessionMemory(20, 4, 16000), repository, 20);
    }

    /** 写入后新建实例（模拟重启）仍能恢复历史。 */
    @Test
    void historySurvivesRestart() {
        fresh().append("s1", "你好", "你好，我是 Vatica");

        List<Message> history = fresh().history("s1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(history.get(0).getText()).isEqualTo("你好");
        assertThat(history.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(history.get(1).getText()).isEqualTo("你好，我是 Vatica");
    }

    /** 重启恢复时同样受滑窗上限约束（只取最近 N 条）。 */
    @Test
    void restoreAppliesSlidingWindow() {
        JpaSessionMemory memory = fresh();
        for (int i = 1; i <= 25; i++) {
            memory.append("s1", "问题" + i, "回答" + i);
        }

        // 25 轮共 50 条消息；恢复时只取最近 20 条 = 第 16~25 轮
        List<Message> history = fresh().history("s1");

        assertThat(history).hasSize(20);
        assertThat(history.get(0).getText()).isEqualTo("问题16");
        assertThat(history.get(history.size() - 1).getText()).isEqualTo("回答25");
    }

    /** 空文本不落库（与内存版语义一致）。 */
    @Test
    void blankTextsNotPersisted() {
        fresh().append("s1", "你好", "");

        assertThat(repository.count()).isEqualTo(1);
    }

    /** 多会话隔离。 */
    @Test
    void sessionsAreIsolated() {
        fresh().append("s1", "A", "a");
        fresh().append("s2", "B", "b");

        assertThat(fresh().history("s1")).hasSize(2);
        assertThat(fresh().history("s2")).hasSize(2);
        assertThat(fresh().history("s1").get(0).getText()).isEqualTo("A");
        assertThat(fresh().history("s2").get(0).getText()).isEqualTo("B");
    }
}
