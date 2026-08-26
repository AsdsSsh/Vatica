package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.context.TokenEstimator;
import com.example.vatica.model.ConversationMessage;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.CalendarTools;
import com.example.vatica.tool.TodoRecordRepository;
import com.example.vatica.tool.TodoTools;

/**
 * 会话记忆持久化集成测试（迭代 5 I5-4）：H2 真库 + 新实例模拟"应用重启"，
 * 验证历史恢复、滑窗裁剪（重启恢复路径）、空文本不落库、多会话隔离。
 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-msg;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class JpaSessionMemoryTest {

    @Autowired
    ChatMessageRecordRepository repository;
    @Autowired TodoTools todoTools;
    @Autowired TodoRecordRepository todoRepository;
    @Autowired CalendarTools calendarTools;
    @Autowired CalendarEventRecordRepository eventRepository;

    @BeforeEach
    void setUp() {
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
        repository.deleteAll();
        todoRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    private JpaSessionMemory fresh() {
        return new JpaSessionMemory(new InMemorySessionMemory(20, 4, 16000), repository, 20);
    }

    private JpaSessionMemory longContextMemory() {
        return new JpaSessionMemory(new InMemorySessionMemory(20, 4, 16000), repository,
                20, null, null, 200);
    }

    /** 写入后新建实例（模拟重启）仍能恢复历史。 */
    @Test
    void historySurvivesRestart() {
        fresh().append("s1", "你好", "你好，我是 Vatica");

        List<ConversationMessage> history = fresh().history("s1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(history.get(0).text()).isEqualTo("你好");
        assertThat(history.get(1).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(history.get(1).text()).isEqualTo("你好，我是 Vatica");
    }

    /** 重启恢复时同样受滑窗上限约束（只取最近 N 条）。 */
    @Test
    void restoreAppliesSlidingWindow() {
        JpaSessionMemory memory = fresh();
        for (int i = 1; i <= 25; i++) {
            memory.append("s1", "问题" + i, "回答" + i);
        }

        // 25 轮共 50 条消息；恢复时只取最近 20 条 = 第 16~25 轮
        List<ConversationMessage> history = fresh().history("s1");

        assertThat(history).hasSize(20);
        assertThat(history.get(0).text()).isEqualTo("问题16");
        assertThat(history.get(history.size() - 1).text()).isEqualTo("回答25");
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
        assertThat(fresh().history("s1").get(0).text()).isEqualTo("A");
        assertThat(fresh().history("s2").get(0).text()).isEqualTo("B");
    }

    /** 同一个 sessionId 在不同用户下是两份独立历史。 */
    @Test
    void sameSessionIdIsIsolatedByUser() {
        fresh().append("shared", "用户一", "回答一");
        RequestIdentityContext.set(new RequestIdentity(2L, 1L, "MEMBER", "other"));
        fresh().append("shared", "用户二", "回答二");

        assertThat(fresh().history("shared").get(0).text()).isEqualTo("用户二");
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
        assertThat(fresh().history("shared").get(0).text()).isEqualTo("用户一");
    }

    /** 同一个用户跨组织也不能复用 JVM 热缓存或数据库近期原文。 */
    @Test
    void sameUserAndSessionAreIsolatedByOrganization() {
        fresh().append("shared-org", "组织一", "回答一");
        RequestIdentityContext.set(new RequestIdentity(1L, 2L, "MEMBER", "same-user"));
        fresh().append("shared-org", "组织二", "回答二");

        assertThat(fresh().history("shared-org")).extracting(ConversationMessage::text)
                .containsExactly("组织二", "回答二");
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
        assertThat(fresh().history("shared-org")).extracting(ConversationMessage::text)
                .containsExactly("组织一", "回答一");
    }

    /** 大窗口读取从数据库按 token 扩大；普通 history 的 20 条热缓存不随之膨胀。 */
    @Test
    void budgetedContextReadCanExceedHotWindowWithoutChangingIt() {
        JpaSessionMemory memory = longContextMemory();
        for (int i = 1; i <= 30; i++) {
            memory.append("long", "问题" + i, "回答" + i);
        }

        SessionMemory.ContextWindow expanded = memory.contextWindow("long",
                new SessionContextReadRequest(2_000, 100));

        assertThat(expanded.recent()).hasSize(60);
        assertThat(expanded.recentStartSeq()).isEqualTo(1);
        assertThat(expanded.recentEndSeq()).isEqualTo(60);
        assertThat(memory.history("long")).hasSize(20);
    }

    /** Token 预算裁剪保留尾部完整消息，并暴露范围供后续证据去重。 */
    @Test
    void budgetedContextReadKeepsCompleteTailMessages() {
        JpaSessionMemory memory = longContextMemory();
        for (int i = 1; i <= 12; i++) {
            memory.append("budget", "问题内容" + i, "回答内容" + i);
        }

        SessionMemory.ContextWindow window = memory.contextWindow("budget",
                new SessionContextReadRequest(16, 100));

        assertThat(window.recent()).isNotEmpty().hasSizeLessThan(24);
        assertThat(window.recent().getLast().text()).isEqualTo("回答内容12");
        assertThat(window.recentStartSeq()).isPositive();
        assertThat(window.recentEndSeq()).isEqualTo(24);
    }

    /** 调用方的 maxMessages 是硬上限；奇数尾页不能制造孤立 assistant。 */
    @Test
    void budgetedContextReadHonorsRowLimitAndStartsWithUser() {
        JpaSessionMemory memory = longContextMemory();
        for (int i = 1; i <= 3; i++) {
            memory.append("row-limit", "问题" + i, "回答" + i);
        }

        SessionMemory.ContextWindow window = memory.contextWindow("row-limit",
                new SessionContextReadRequest(1_000, 3));

        assertThat(window.recent()).hasSize(2);
        assertThat(window.recent().getFirst().role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(window.recent()).extracting(ConversationMessage::text)
                .containsExactly("问题3", "回答3");
    }

    /** 一轮整体超预算时完整跳过，不能只保留很短的 assistant 回答。 */
    @Test
    void budgetedContextReadDoesNotKeepOrphanAssistantFromOversizedTurn() {
        repository.saveAll(List.of(
                new ChatMessageRecord(1L, 1L, "oversized", "USER", "很长".repeat(200), 1),
                new ChatMessageRecord(1L, 1L, "oversized", "ASSISTANT", "好", 2)));

        SessionMemory.ContextWindow window = longContextMemory().contextWindow("oversized",
                new SessionContextReadRequest(8, 10));

        assertThat(window.recent()).isEmpty();
        assertThat(window.recentStartSeq()).isZero();
        assertThat(window.recentEndSeq()).isZero();
    }

    /** 调用方即使请求更大行数，也不能绕过服务端配置的数据库读取护栏。 */
    @Test
    void budgetedContextReadHonorsConfiguredMaxMessages() {
        JpaSessionMemory memory = longContextMemory();
        for (int i = 1; i <= 110; i++) {
            memory.append("row-limit", "问" + i, "答" + i);
        }

        SessionMemory.ContextWindow window = memory.contextWindow("row-limit",
                new SessionContextReadRequest(100_000, 10_000));

        assertThat(window.recent()).hasSize(200);
        assertThat(window.recentStartSeq()).isEqualTo(21);
        assertThat(window.recentEndSeq()).isEqualTo(220);
    }

    /** 预算不足一整轮时不能只回灌末尾 assistant，且实际选中内容不能突破预算。 */
    @Test
    void overBudgetTurnDoesNotReturnOrphanAssistantOrExceedBudget() {
        JpaSessionMemory memory = longContextMemory();
        memory.append("atomic-turn", "这是一条明显超过预算的用户指令", "好");
        int tokenBudget = TokenEstimator.estimate("好");

        SessionMemory.ContextWindow window = memory.contextWindow("atomic-turn",
                new SessionContextReadRequest(tokenBudget, 100));

        assertThat(window.recent()).isEmpty();
        assertThat(TokenEstimator.estimate(window.recent().stream()
                .map(ConversationMessage::text).toList())).isLessThanOrEqualTo(tokenBudget);
    }

    /** 日历与待办生产 Bean 使用数据库，并按当前用户过滤。 */
    @Test
    void pimDataIsIsolatedByUser() {
        todoTools.add("用户一待办", "2026-08-20");
        calendarTools.create("用户一日程", "2026-08-20T09:00", "2026-08-20T10:00", null);

        RequestIdentityContext.set(new RequestIdentity(2L, 1L, "MEMBER", "other"));
        assertThat(todoTools.list()).contains("待办清单为空");
        assertThat(calendarTools.query("2026-08-20", "2026-08-21")).contains("没有日程");

        todoTools.add("用户二待办", null);
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
        assertThat(todoTools.list()).contains("用户一待办").doesNotContain("用户二待办");
    }
}
