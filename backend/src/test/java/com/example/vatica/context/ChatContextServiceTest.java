package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ContextAllocationProperties;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.controller.ChatMessageRecordRepository;
import com.example.vatica.controller.InMemorySessionMemory;
import com.example.vatica.controller.JpaSessionMemory;
import com.example.vatica.controller.SessionContextReadRequest;
import com.example.vatica.controller.SessionMemory;
import com.example.vatica.controller.SessionMemory.ContextWindow;
import com.example.vatica.controller.SessionSummaryStatus;
import com.example.vatica.model.ConversationMessage;

/** 迭代 31D：请求模式、长历史读取、证据注入和降级状态的聚焦回归。 */
class ChatContextServiceTest {

    @Test
    void normalModeUsesBudgetedHistoryButNeverSearchesHistoricalEvidence() {
        SessionMemory memory = mock(SessionMemory.class);
        ConversationEvidenceRetriever evidence = mock(ConversationEvidenceRetriever.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(window(1, 4, List.of(
                        ConversationMessage.user("近期问题"), ConversationMessage.assistant("近期回答"))));
        ChatContextService service = service(memory, null, evidence);

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.NORMAL, 128_000));

        assertThat(prepared.status().effectiveMode()).isEqualTo(ContextMode.NORMAL);
        assertThat(prepared.status().evidenceStatus()).isEqualTo(ConversationEvidenceStatus.SKIPPED_MODE);
        assertThat(prepared.history()).extracting(ConversationMessage::text)
                .contains("近期问题", "近期回答");
        verify(evidence, never()).retrieve(any(), any(), anyLong(), anyInt());
        verify(memory, org.mockito.Mockito.atLeastOnce())
                .contextWindow(eq("s1"), any(SessionContextReadRequest.class));
    }

    @Test
    void normalModeProbeNeverExceedsItsDynamicCapOnSmallModelWindow() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(window(1, 2, List.of(ConversationMessage.user("近期问题"))));
        ChatContextService service = service(memory, null, mock(ConversationEvidenceRetriever.class));

        service.prepare(request(ContextMode.NORMAL, 16_000));

        ArgumentCaptor<SessionContextReadRequest> reads = ArgumentCaptor.forClass(SessionContextReadRequest.class);
        verify(memory, org.mockito.Mockito.atLeastOnce()).contextWindow(eq("s1"), reads.capture());
        ContextAllocationPlan initialPlan = new ContextAllocationPlanner(new ContextAllocationProperties()).plan(
                new ModelCapabilityProfile("test-model", 16_000, 16_000,
                        ModelCapabilityProfile.ESTIMATED_TOKENIZER, true),
                ContextMode.NORMAL, new ContextFixedInput(
                        TokenEstimator.estimate("系统规则"), 0, TokenEstimator.estimate("请核对旧预算"), 0, 0));
        assertThat(reads.getAllValues().getFirst().recentTokenBudget())
                .isEqualTo(Math.min(initialPlan.requestedDynamic().recentHistoryTokens(),
                        initialPlan.modeDynamicCapTokens()));
    }

    @Test
    void longTaskRetrievesOnlyBeforeRecentWindowAndInjectsUntrustedEvidenceFirst() {
        SessionMemory memory = mock(SessionMemory.class);
        ConversationEvidenceRetriever evidence = mock(ConversationEvidenceRetriever.class);
        ContextWindow window = window(101, 104, List.of(
                ConversationMessage.user("近期问题"), ConversationMessage.assistant("近期回答")));
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class))).thenReturn(window);
        ConversationEvidenceResult match = new ConversationEvidenceResult(
                ConversationEvidenceStatus.MATCHED,
                List.of(new ConversationEvidenceSnippet(10, 11, "证据", 20)),
                "【按需检索的历史会话原文】\n旧预算为 42\n【历史会话原文结束】", 40, 3);
        when(evidence.retrieve(eq("s1"), eq("请核对旧预算"), eq(101L), anyInt())).thenReturn(match);
        ChatContextService service = service(memory, null, evidence);

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.LONG_TASK, 200_000));

        assertThat(prepared.status().effectiveMode()).isEqualTo(ContextMode.LONG_TASK);
        assertThat(prepared.status().evidenceStatus()).isEqualTo(ConversationEvidenceStatus.MATCHED);
        assertThat(prepared.status().evidenceCandidateCount()).isEqualTo(3);
        assertThat(prepared.history().getFirst().text()).contains("历史会话原文").contains("旧预算为 42");
        verify(evidence).retrieve(eq("s1"), eq("请核对旧预算"), eq(101L),
                eq(prepared.plan().dynamicBudget().historicalEvidenceTokens()));
    }

    @Test
    void deepReviewFallsBackToLongTaskWhenModelWindowIsTooSmall() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(window(1, 2, List.of(ConversationMessage.user("历史"))));
        ChatContextService service = service(memory, null, mock(ConversationEvidenceRetriever.class));

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.DEEP_REVIEW, 128_000));

        assertThat(prepared.status().requestedMode()).isEqualTo(ContextMode.DEEP_REVIEW);
        assertThat(prepared.status().effectiveMode()).isEqualTo(ContextMode.LONG_TASK);
        assertThat(prepared.status().modeDowngraded()).isTrue();
    }

    @Test
    void databaseReadFailureFallsBackToHotWindowWithoutBlockingChat() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenThrow(new IllegalStateException("database unavailable with sensitive text"));
        when(memory.hotContextWindow("s1")).thenReturn(window(9, 10,
                List.of(ConversationMessage.user("热窗口"), ConversationMessage.assistant("仍可回答"))));
        ChatContextService service = service(memory, null, mock(ConversationEvidenceRetriever.class));

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.NORMAL, 128_000));

        assertThat(prepared.status().historyStatus()).isEqualTo("FALLBACK");
        assertThat(prepared.history()).extracting(ConversationMessage::text)
                .contains("热窗口", "仍可回答");
    }

    @Test
    void jpaDatabaseFailureUsesJvmHotCacheInsteadOfQueryingDatabaseAgain() {
        RequestIdentityContext.set(new RequestIdentity(7L, 3L, "MEMBER", "tester"));
        try {
            ChatMessageRecordRepository repository = mock(ChatMessageRecordRepository.class);
            when(repository.findByOrgIdAndUserIdAndSessionIdOrderBySeqDesc(
                    eq(3L), eq(7L), eq("s1"), any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(List.of());
            JpaSessionMemory memory = new JpaSessionMemory(
                    new InMemorySessionMemory(20, 4, 16_000), repository, 20,
                    null, null, 512);
            memory.append(" s1 ", "热缓存问题", "热缓存回答");

            reset(repository);
            when(repository.findByOrgIdAndUserIdAndSessionIdOrderBySeqDesc(
                    eq(3L), eq(7L), eq("s1"), any(org.springframework.data.domain.Pageable.class)))
                    .thenThrow(new IllegalStateException("database unavailable"));
            ChatContextService service = service(memory, null, mock(ConversationEvidenceRetriever.class));

            ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.NORMAL, 128_000));

            assertThat(prepared.status().historyStatus()).isEqualTo("FALLBACK");
            assertThat(prepared.history()).extracting(ConversationMessage::text)
                    .contains("热缓存问题", "热缓存回答");
        } finally {
            RequestIdentityContext.clear();
        }
    }

    @Test
    void summaryFailureInjectsOperationalMaterialsIntoDegradedHistory() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(new ContextWindow("旧摘要", SessionSummaryStatus.FAILED, 2, 2, 0,
                        List.of(), List.of(),
                        List.of(ConversationMessage.user("近期问题")), 3, 3));
        ContextOperationalMaterialService operational = mock(ContextOperationalMaterialService.class);
        when(operational.resolveForChat(eq("s1"), isNull(), eq(true)))
                .thenReturn(new ContextOperationalMaterials(List.of(new ContextOperationalMaterials.Snippet(
                        ContextOperationalMaterials.TASK_STATE, "task:t-1", "",
                        "status=RUNNING; currentStep=1")), true, false));
        ChatContextService service = serviceWithOperational(memory, operational);

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.NORMAL, 128_000));

        assertThat(prepared.history()).extracting(ConversationMessage::text)
                .anyMatch(text -> text.contains("【关键工具、审批和交付物记录】"))
                .anyMatch(text -> text.contains("task:t-1"))
                .contains("近期问题");
    }

    @Test
    void healthySummaryDoesNotReadOrInjectOperationalMaterials() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(window(1, 2, List.of(ConversationMessage.user("近期问题"))));
        ContextOperationalMaterialService operational = mock(ContextOperationalMaterialService.class);
        ChatContextService service = serviceWithOperational(memory, operational);

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.NORMAL, 128_000));

        verify(operational, never()).resolveForChat(any(), any(), anyBoolean());
        assertThat(prepared.history()).extracting(ConversationMessage::text)
                .noneMatch(text -> text.contains("关键工具、审批和交付物记录"))
                .contains("近期问题");
    }

    @Test
    void explicitTaskIdInjectsOperationalMaterialsEvenWithoutDegradation() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(window(1, 2, List.of(ConversationMessage.user("近期问题"))));
        ContextOperationalMaterialService operational = mock(ContextOperationalMaterialService.class);
        when(operational.resolveForChat(eq("s1"), eq("t-9"), eq(true)))
                .thenReturn(new ContextOperationalMaterials(List.of(new ContextOperationalMaterials.Snippet(
                        ContextOperationalMaterials.APPROVAL, "task:t-9/step:2", "PENDING",
                        "description=写入文档")), true, false));
        ChatContextService service = serviceWithOperational(memory, operational);

        ChatContextService.PreparedChatContext prepared = service.prepare(
                new ChatContextService.PreparationRequest("s1", ContextMode.NORMAL,
                        new ModelCapabilityProfile("test-model", 128_000, 16_000,
                                ModelCapabilityProfile.ESTIMATED_TOKENIZER, true),
                        "系统规则", "请继续任务", new io.agentscope.core.tool.AgentTool[0], "t-9"));

        assertThat(prepared.history()).extracting(ConversationMessage::text)
                .anyMatch(text -> text.contains("task:t-9/step:2"))
                .anyMatch(text -> text.contains("【关键工具、审批和交付物记录】"));
    }

    @Test
    void operationalMaterialFailureStillLeavesExplicitBoundaryInDegradedHistory() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(new ContextWindow("旧摘要", SessionSummaryStatus.FAILED, 2, 2, 0,
                        List.of(), List.of(),
                        List.of(ConversationMessage.user("近期问题")), 3, 3));
        ContextOperationalMaterialService operational = mock(ContextOperationalMaterialService.class);
        when(operational.resolveForChat(any(), any(), anyBoolean())).thenThrow(new IllegalStateException("db down"));
        ChatContextService service = serviceWithOperational(memory, operational);

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.NORMAL, 128_000));

        assertThat(prepared.history()).extracting(ConversationMessage::text)
                .anyMatch(text -> text.contains("【关键工具、审批和交付物记录】"))
                .anyMatch(text -> text.contains("读取部分操作记录失败"));
    }

    @Test
    void evidenceFailureTriggersOperationalLookupEvenWithoutInitialDegradation() {
        SessionMemory memory = mock(SessionMemory.class);
        when(memory.contextWindow(eq("s1"), any(SessionContextReadRequest.class)))
                .thenReturn(new ContextWindow("历史摘要", SessionSummaryStatus.SUCCESS, 8, 8, 2,
                        List.of(), List.of(),
                        List.of(ConversationMessage.user("近期问题"), ConversationMessage.assistant("近期回答")),
                        8, 9));
        ConversationEvidenceRetriever evidence = mock(ConversationEvidenceRetriever.class);
        when(evidence.retrieve(any(), any(), anyLong(), anyInt()))
                .thenReturn(ConversationEvidenceResult.empty(ConversationEvidenceStatus.UNAVAILABLE));
        ContextOperationalMaterialService operational = mock(ContextOperationalMaterialService.class);
        when(operational.resolveForChat(eq("s1"), isNull(), eq(true)))
                .thenReturn(new ContextOperationalMaterials(List.of(new ContextOperationalMaterials.Snippet(
                        ContextOperationalMaterials.TOOL, "task:t-1/tool:calendar.search", "SUCCESS",
                        "output=返回 3 条日程")), true, false));
        ChatContextService service = new ChatContextService(memory, null, evidence,
                new ContextAllocationPlanner(new ContextAllocationProperties()),
                new ChatProperties(null, new ChatProperties.Memory(20, 64, 16_000, 512), null), operational);

        ChatContextService.PreparedChatContext prepared = service.prepare(request(ContextMode.LONG_TASK, 128_000));

        verify(operational).resolveForChat(eq("s1"), isNull(), eq(true));
        assertThat(prepared.history()).extracting(ConversationMessage::text)
                .anyMatch(text -> text.contains("calendar.search"));
    }

    private static ChatContextService service(SessionMemory memory, ContextFactService facts,
            ConversationEvidenceRetriever evidence) {
        ChatProperties properties = new ChatProperties(null,
                new ChatProperties.Memory(20, 64, 16_000, 512), null);
        return new ChatContextService(memory, facts, evidence,
                new ContextAllocationPlanner(new ContextAllocationProperties()), properties);
    }

    private static ChatContextService serviceWithOperational(SessionMemory memory,
            ContextOperationalMaterialService operational) {
        ChatProperties properties = new ChatProperties(null,
                new ChatProperties.Memory(20, 64, 16_000, 512), null);
        return new ChatContextService(memory, null, mock(ConversationEvidenceRetriever.class),
                new ContextAllocationPlanner(new ContextAllocationProperties()), properties, operational);
    }

    private static ChatContextService.PreparationRequest request(ContextMode mode, int windowTokens) {
        return new ChatContextService.PreparationRequest("s1", mode,
                new ModelCapabilityProfile("test-model", windowTokens, 16_000,
                        ModelCapabilityProfile.ESTIMATED_TOKENIZER, true),
                "系统规则", "请核对旧预算", new io.agentscope.core.tool.AgentTool[0]);
    }

    private static ContextWindow window(long startSeq, long endSeq, List<ConversationMessage> recent) {
        return new ContextWindow("历史摘要", SessionSummaryStatus.SUCCESS, Math.max(0, startSeq - 1),
                Math.max(0, startSeq - 1), recent.size(), List.of(), List.of(), recent, startSeq, endSeq);
    }
}
