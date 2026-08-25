package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import com.example.vatica.controller.SessionMemory;
import com.example.vatica.controller.SessionMemory.ContextWindow;
import com.example.vatica.model.ConversationMessage;

/** 迭代 15 I15-8：token 估算与上下文裁剪——中文 1 字 1 token、其他 4 字符 1 token、保护最新消息。 */
class ContextBudgetTest {

    @Test
    void estimatesChineseAsOneTokenPerCharAndAsciiAsQuarter() {
        assertThat(TokenEstimator.estimate("你好")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("abc")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("你好abcd")).isEqualTo(3);
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate((String) null)).isZero();
    }

    @Test
    void budgetDefaultsToPlannedSizes() {
        ContextBudget budget = new ContextBudget(0, 0, 0, 0, 0);

        assertThat(budget.tokensFor(ContextBudget.CallSite.CHAT)).isEqualTo(16_000);
        assertThat(budget.tokensFor(ContextBudget.CallSite.PLANNER)).isEqualTo(8_000);
        assertThat(budget.tokensFor(ContextBudget.CallSite.EXECUTOR)).isEqualTo(12_000);
        assertThat(budget.tokensFor(ContextBudget.CallSite.JUDGE)).isEqualTo(16_000);
        assertThat(budget.tokensFor(ContextBudget.CallSite.SUMMARIZER)).isEqualTo(8_000);
    }

    @Test
    void trimDropsOldestUntilBudgetAndKeepsProtectedTail() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("一二三四五六七八九十"),   // 10 tokens
                ConversationMessage.assistant("abcdefgh"),          // 2 tokens
                ConversationMessage.user("最新问题"));               // 4 tokens

        List<ConversationMessage> trimmed = ContextTrimmer.trim(messages, 6, 1);

        assertThat(trimmed).hasSize(2);
        assertThat(trimmed.get(0).text()).isEqualTo("abcdefgh");
        assertThat(trimmed.get(1).text()).isEqualTo("最新问题");
        assertThat(ContextTrimmer.estimateTokens(trimmed)).isLessThanOrEqualTo(6);
    }

    @Test
    void oversizeTailIsKeptWholeRatherThanTruncated() {
        List<ConversationMessage> messages = List.of(
                ConversationMessage.user("老消息"),
                ConversationMessage.user("这是一条超过预算也必须完整保留的最新问题"));

        List<ConversationMessage> trimmed = ContextTrimmer.trim(messages, 5, 1);

        assertThat(trimmed).hasSize(1);
        assertThat(trimmed.get(0).text()).contains("必须完整保留");
    }

    @Test
    void chatHistoryPrependsSummaryAndKeepsRecentWithinBudget() {
        SessionMemory memory = new SessionMemory() {
            @Override public List<ConversationMessage> history(String sessionId) { return recent(sessionId); }
            @Override public void append(String sessionId, String userText, String assistantText) { }
            @Override public String summary(String sessionId) { return "用户偏好周报，周三交付"; }
            @Override public List<ConversationMessage> recent(String sessionId) {
                return List.of(ConversationMessage.user("刚才问的问题"),
                        ConversationMessage.assistant("刚才的回答"));
            }
        };

        List<ConversationMessage> history = ContextAssembler.chatHistory(
                memory, "s1", new ContextBudget(100, 100, 100, 100, 100));

        assertThat(history).hasSize(3);
        assertThat(history.get(0).text()).contains("【历史会话摘要】").contains("周三交付");
        assertThat(history.get(1).text()).isEqualTo("刚才问的问题");
        assertThat(history.get(2).text()).isEqualTo("刚才的回答");
    }

    @Test
    void degradedHistoryKeepsSummaryAndBoundaryWhenBudgetEvictsOptionalFragments() {
        SessionMemory memory = new SessionMemory() {
            @Override public List<ConversationMessage> history(String sessionId) { return List.of(); }
            @Override public void append(String sessionId, String userText, String assistantText) { }
            @Override public String summary(String sessionId) { return "已确认的会议结论"; }
            @Override public List<ConversationMessage> recent(String sessionId) {
                return List.of(ConversationMessage.user("近期问题"), ConversationMessage.assistant("近期回答"));
            }
            @Override public ContextWindow contextWindow(String sessionId) {
                return new ContextWindow("已确认的会议结论",
                        com.example.vatica.controller.SessionSummaryStatus.FAILED,
                        2, 20, 18,
                        List.of(ConversationMessage.user("未摘要头部证据")),
                        List.of(ConversationMessage.assistant("未摘要尾部证据")),
                        recent(sessionId));
            }
        };

        List<ConversationMessage> history = ContextAssembler.chatHistory(
                memory, "s1", new ContextBudget(20, 100, 100, 100, 100));

        assertThat(history).anyMatch(message -> message.text().contains("历史会话摘要"));
        assertThat(history).anyMatch(message -> message.text().contains("未摘要历史降级边界"));
        assertThat(history.get(history.size() - 1).text()).isEqualTo("近期回答");
    }
}
