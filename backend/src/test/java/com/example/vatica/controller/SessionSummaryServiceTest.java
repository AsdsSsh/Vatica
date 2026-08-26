package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelResponse;
import com.example.vatica.model.ModelUsage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 迭代 15 I15-9：中期滚动摘要——成功推进水位线；失败不推进（下次自然重试）。
 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-summary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class SessionSummaryServiceTest {

    @MockitoBean
    ModelRegistry registry;
    @MockitoBean
    ModelGateway modelGateway;

    @Autowired
    SessionSummaryService summaryService;
    @Autowired
    ChatSessionRecordRepository sessions;
    @Autowired
    ChatMessageRecordRepository messages;
    @Autowired
    ChatSummarySegmentRecordRepository segments;
    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
        sessions.deleteAll();
        messages.deleteAll();
        segments.deleteAll();
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    private void seedMessages(String sessionId, int from, int to) {
        List<ChatMessageRecord> rows = new java.util.ArrayList<>();
        for (int i = from; i <= to; i++) {
            rows.add(new ChatMessageRecord(1L, 1L, sessionId, i % 2 == 1 ? "USER" : "ASSISTANT",
                    "内容" + i, i));
        }
        messages.saveAll(rows);
    }

    @Test
    void successfulSummaryAdvancesWatermarkAndPersistsText() {
        seedMessages("s1", 1, 10);
        messages.save(new ChatMessageRecord(1L, 2L, "s1", "USER", "其他组织内容", 1));
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenReturn(
                new ModelResponse("用户偏好：周报周三交付", "", ModelUsage.empty()));

        summaryService.summarize(1L, 1L, "s1", 5);

        ChatSessionRecord session = sessions.findByUserIdAndSessionId(1L, "s1").orElseThrow();
        assertThat(session.getSummaryText()).contains("周报周三交付");
        assertThat(session.getSummaryThroughSeq()).isEqualTo(5);
        assertThat(session.getSummaryTokens()).isGreaterThan(0);
        assertThat(session.getSummaryStatus()).isEqualTo(SessionSummaryStatus.SUCCESS);
        assertThat(session.getSummaryFailureCode()).isEqualTo(SessionSummaryFailureCode.NONE);
        List<ChatSummarySegmentRecord> localSegments = segments
                .findByOrgIdAndUserIdAndSessionIdAndSummaryLevelOrderByStartSeqAsc(
                        1L, 1L, "s1", ChatSummarySegmentLevel.L1_LOCAL);
        assertThat(localSegments).singleElement().satisfies(segment -> {
            assertThat(segment.getStartSeq()).isEqualTo(1);
            assertThat(segment.getEndSeq()).isEqualTo(5);
            assertThat(segment.getSourceMessageCount()).isEqualTo(5);
            assertThat(segment.getEstimatedTokens()).isGreaterThan(0);
            assertThat(segment.getSourceFingerprint()).hasSize(64);
            assertThat(segment.getStrategyVersion()).isEqualTo("summary-v2-json-local");
            assertThat(segment.getModelId()).isEqualTo("summary-model");
        });
        assertThat(segments.countByOrgIdAndUserIdAndSessionId(2L, 1L, "s1")).isZero();
    }

    @Test
    void failedSummaryDoesNotAdvanceWatermark() {
        seedMessages("s1", 1, 10);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenThrow(new RuntimeException("上游 401"));

        summaryService.summarize(1L, 1L, "s1", 5);

        ChatSessionRecord session = sessions.findByUserIdAndSessionId(1L, "s1").orElseThrow();
        assertThat(session.getSummaryThroughSeq()).isZero();
        assertThat(session.getSummaryText()).isNull();
        assertThat(session.getSummaryStatus()).isEqualTo(SessionSummaryStatus.FAILED);
        assertThat(session.getSummaryFailureCode()).isEqualTo(SessionSummaryFailureCode.CONFIGURATION);
        assertThat(session.getSummaryNextRetryAt()).isNull();
        assertThat(segments.countByOrgIdAndUserIdAndSessionId(1L, 1L, "s1")).isZero();
    }

    @Test
    void summaryProcessesBoundedBatchAndLeavesPendingWatermark() {
        seedMessages("s1", 1, 50);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenReturn(
                new ModelResponse("第一批摘要", "", ModelUsage.empty()));

        summaryService.summarize(1L, 1L, "s1", 50);

        ChatSessionRecord session = sessions.findByUserIdAndSessionId(1L, "s1").orElseThrow();
        assertThat(session.getSummaryThroughSeq()).isEqualTo(20);
        assertThat(session.getSummaryRequestedThroughSeq()).isEqualTo(50);
        assertThat(session.getSummaryStatus()).isEqualTo(SessionSummaryStatus.PENDING);
    }

    @Test
    void consecutiveBatchesAppendNonOverlappingLocalSegments() {
        seedMessages("s1", 1, 45);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenReturn(
                new ModelResponse("{\"overview\":\"总览一\",\"localSegment\":\"局部一\"}", "", ModelUsage.empty()),
                new ModelResponse("{\"overview\":\"总览二\",\"localSegment\":\"局部二\"}", "", ModelUsage.empty()));

        summaryService.summarize(1L, 1L, "s1", 45);
        summaryService.summarize(1L, 1L, "s1", 45);

        List<ChatSummarySegmentRecord> localSegments = segments
                .findByOrgIdAndUserIdAndSessionIdAndSummaryLevelOrderByStartSeqAsc(
                        1L, 1L, "s1", ChatSummarySegmentLevel.L1_LOCAL);
        assertThat(localSegments).hasSize(2);
        assertThat(localSegments.get(0).getStartSeq()).isEqualTo(1);
        assertThat(localSegments.get(0).getEndSeq()).isEqualTo(20);
        assertThat(localSegments.get(0).getText()).isEqualTo("局部一");
        assertThat(localSegments.get(1).getStartSeq()).isEqualTo(21);
        assertThat(localSegments.get(1).getEndSeq()).isEqualTo(40);
        assertThat(localSegments.get(1).getText()).isEqualTo("局部二");
        assertThat(localSegments.get(0).getEndSeq()).isLessThan(localSegments.get(1).getStartSeq());
        assertThat(sessions.findByUserIdAndSessionId(1L, "s1").orElseThrow().getSummaryThroughSeq()).isEqualTo(40);
    }

    /** 多实例等价并发中，较晚到达的失败不能覆盖已经提交的成功水位。 */
    @Test
    void concurrentFailureDoesNotOverwriteSuccessfulSummary() throws Exception {
        seedMessages("concurrent", 1, 10);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        CountDownLatch bothInvoked = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        when(modelGateway.call(any())).thenAnswer(ignored -> {
            int call = calls.incrementAndGet();
            bothInvoked.countDown();
            assertThat(bothInvoked.await(5, TimeUnit.SECONDS)).isTrue();
            if (call == 1) {
                return new ModelResponse(
                        "{\"overview\":\"并发成功\",\"localSegment\":\"局部成功\"}",
                        "", ModelUsage.empty());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (sessions.findByUserIdAndOrgIdAndSessionId(1L, 1L, "concurrent")
                        .map(ChatSessionRecord::getSummaryThroughSeq).orElse(0L) >= 5) {
                    throw new RuntimeException("上游 503");
                }
                Thread.sleep(10);
            }
            throw new AssertionError("等待并发成功水位超时");
        });

        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> summaryService.summarize(1L, 1L, "concurrent", 5));
        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> summaryService.summarize(1L, 1L, "concurrent", 5));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        ChatSessionRecord session = sessions
                .findByUserIdAndOrgIdAndSessionId(1L, 1L, "concurrent").orElseThrow();
        assertThat(session.getSummaryThroughSeq()).isEqualTo(5);
        assertThat(session.getSummaryStatus()).isEqualTo(SessionSummaryStatus.SUCCESS);
        assertThat(session.getSummaryFailureCode()).isEqualTo(SessionSummaryFailureCode.NONE);
        assertThat(segments.countByOrgIdAndUserIdAndSessionId(1L, 1L, "concurrent")).isEqualTo(1);
    }

    /** 删除期间在途模型结果只能丢弃，不能重建已删除的会话或摘要段。 */
    @Test
    void inFlightSummaryDoesNotRecreateDeletedSession() throws Exception {
        seedMessages("deleted", 1, 10);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        CountDownLatch modelInvoked = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        when(modelGateway.call(any())).thenAnswer(ignored -> {
            modelInvoked.countDown();
            assertThat(releaseModel.await(5, TimeUnit.SECONDS)).isTrue();
            return new ModelResponse(
                    "{\"overview\":\"不应写入\",\"localSegment\":\"不应写入\"}",
                    "", ModelUsage.empty());
        });

        CompletableFuture<Void> running = CompletableFuture.runAsync(
                () -> summaryService.summarize(1L, 1L, "deleted", 5));
        assertThat(modelInvoked.await(5, TimeUnit.SECONDS)).isTrue();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            sessions.findForUpdate(1L, 1L, "deleted").orElseThrow();
            sessions.deleteByOrgIdAndUserIdAndSessionId(1L, 1L, "deleted");
        });
        releaseModel.countDown();
        running.get(10, TimeUnit.SECONDS);

        assertThat(sessions.findByUserIdAndOrgIdAndSessionId(1L, 1L, "deleted")).isEmpty();
        assertThat(segments.countByOrgIdAndUserIdAndSessionId(1L, 1L, "deleted")).isZero();
    }

    private static ModelSlot summarySlot() {
        return new ModelSlot("summary", "Summary", "openai", "https://example.test",
                "k", "summary-model", 0.2, true);
    }
}
