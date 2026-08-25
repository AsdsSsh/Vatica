package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.controller.ChatSessionRecord;
import com.example.vatica.controller.ChatSessionRecordRepository;
import com.example.vatica.controller.SessionMemory;
import com.example.vatica.controller.SessionMemory.ContextWindow;
import com.example.vatica.controller.SessionSummaryFailureCode;
import com.example.vatica.controller.SessionSummaryStatus;
import com.example.vatica.model.ConversationMessage;
import com.example.vatica.task.TaskRecordRepository;
import com.example.vatica.task.TaskStatus;

/** 迭代 29D：健康状态分类、故障降级和双租户查询边界。 */
class ContextHealthServiceTest {

    private final RequestIdentity identity = new RequestIdentity(7L, 9L, "USER", "tester");

    @BeforeEach
    void setIdentity() {
        RequestIdentityContext.set(identity);
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void classifiesHealthyProcessingAndDegradedSessionStates() {
        assertThat(ContextHealthService.classifySession(SessionSummaryStatus.SUCCESS, false, 4, 4, 0))
                .isEqualTo(ContextHealthStatus.HEALTHY);
        assertThat(ContextHealthService.classifySession(SessionSummaryStatus.PENDING, false, 8, 4, 0))
                .isEqualTo(ContextHealthStatus.PROCESSING);
        assertThat(ContextHealthService.classifySession(SessionSummaryStatus.FAILED, false, 8, 4, 0))
                .isEqualTo(ContextHealthStatus.DEGRADED);
        assertThat(ContextHealthService.classifySession(SessionSummaryStatus.SUCCESS, true, 4, 4, 0))
                .isEqualTo(ContextHealthStatus.DEGRADED);
    }

    @Test
    void staleFactsAlwaysWinOverSummaryState() {
        assertThat(ContextHealthService.classifySession(SessionSummaryStatus.SUCCESS, false, 4, 4, 2))
                .isEqualTo(ContextHealthStatus.NEEDS_REFRESH);
        assertThat(ContextHealthService.classifyTask(TaskStatus.DONE, false, 1, false))
                .isEqualTo(ContextHealthStatus.NEEDS_REFRESH);
    }

    @Test
    void sessionViewContainsOnlySafeHealthMetadata() {
        SessionMemory memory = mock(SessionMemory.class);
        ChatSessionRecordRepository sessions = mock(ChatSessionRecordRepository.class);
        ContextFactService facts = mock(ContextFactService.class);
        TaskRecordRepository tasks = mock(TaskRecordRepository.class);
        ChatSessionRecord record = new ChatSessionRecord(7L, 9L, "s1", "工作会话");
        record.setSummaryStatus(SessionSummaryStatus.FAILED);
        record.setSummaryFailureCode(SessionSummaryFailureCode.TIMEOUT);
        record.setSummaryThroughSeq(10);
        record.setSummaryRequestedThroughSeq(14);
        record.setSummaryAttemptCount(2);
        when(sessions.findByUserIdAndOrgIdAndSessionId(7L, 9L, "s1")).thenReturn(Optional.of(record));
        when(memory.contextWindow("s1")).thenReturn(new ContextWindow("不应返回的摘要",
                SessionSummaryStatus.FAILED, 10, 14, 6,
                List.of(ConversationMessage.user("头部")), List.of(ConversationMessage.assistant("尾部")),
                List.of(ConversationMessage.user("近期"))));
        when(facts.listActive(identity, ContextFactScopeType.CHAT_SESSION, "s1")).thenReturn(List.of());
        when(facts.resolveCurrent(ContextFactScopeType.CHAT_SESSION, "s1")).thenReturn(List.of());

        ContextHealthView view = new ContextHealthService(memory, sessions, facts, tasks)
                .get(ContextFactScopeType.CHAT_SESSION, "s1");

        assertThat(view.overallStatus()).isEqualTo(ContextHealthStatus.DEGRADED);
        assertThat(view.reason()).isEqualTo("SUMMARY_FAILED_TIMEOUT");
        assertThat(view.summaryThroughSeq()).isEqualTo(10);
        assertThat(view.fallbackHeadCount()).isEqualTo(1);
        assertThat(view.fallbackTailCount()).isEqualTo(1);
        assertThat(view.summaryStatus()).isEqualTo(SessionSummaryStatus.FAILED);
        assertThat(view.summaryFailureCode()).isEqualTo(SessionSummaryFailureCode.TIMEOUT);
    }

    @Test
    void wrongOrganizationCannotProbeSessionMemory() {
        SessionMemory memory = mock(SessionMemory.class);
        ChatSessionRecordRepository sessions = mock(ChatSessionRecordRepository.class);
        ContextFactService facts = mock(ContextFactService.class);
        TaskRecordRepository tasks = mock(TaskRecordRepository.class);
        when(sessions.findByUserIdAndOrgIdAndSessionId(7L, 9L, "other"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ContextHealthService(memory, sessions, facts, tasks)
                .get(ContextFactScopeType.CHAT_SESSION, "other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
        verifyNoInteractions(memory, facts);
    }

    @Test
    void memoryFailureIsReportedAsDegradedInsteadOfServerError() {
        SessionMemory memory = mock(SessionMemory.class);
        ChatSessionRecordRepository sessions = mock(ChatSessionRecordRepository.class);
        ContextFactService facts = mock(ContextFactService.class);
        TaskRecordRepository tasks = mock(TaskRecordRepository.class);
        ChatSessionRecord record = new ChatSessionRecord(7L, 9L, "s2", "会话");
        record.setSummaryStatus(SessionSummaryStatus.SUCCESS);
        when(sessions.findByUserIdAndOrgIdAndSessionId(7L, 9L, "s2")).thenReturn(Optional.of(record));
        doThrow(new IllegalStateException("database temporarily unavailable"))
                .when(memory).contextWindow("s2");
        when(facts.listActive(identity, ContextFactScopeType.CHAT_SESSION, "s2")).thenReturn(List.of());
        when(facts.resolveCurrent(ContextFactScopeType.CHAT_SESSION, "s2")).thenReturn(List.of());

        ContextHealthView view = new ContextHealthService(memory, sessions, facts, tasks)
                .get(ContextFactScopeType.CHAT_SESSION, "s2");

        assertThat(view.overallStatus()).isEqualTo(ContextHealthStatus.DEGRADED);
        assertThat(view.reason()).isEqualTo("MEMORY_UNAVAILABLE");
    }
}
