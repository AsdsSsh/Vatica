package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.controller.ChatMessageRecord;
import com.example.vatica.controller.ChatMessageRecordRepository;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelResponse;
import com.example.vatica.model.ModelUsage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 迭代 34：Agent 推断事实的异步后置抽取——尽力而为语义：
 * 模型失败/烂输出不入库不抛出；候选级容错；重复抽取靠 equivalent 去重不膨胀 revision。
 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-fact-extract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class ContextFactExtractionServiceTest {

    private static final String VALID_OUTPUT = "{\"facts\":[{\"factKey\":\"report.delivery.day\","
            + "\"factType\":\"DATE_TIME\",\"valueJson\":\"{\\\"day\\\":\\\"周三\\\"}\","
            + "\"displaySummary\":\"用户倾向周三交付报告\"}]}";
    private static final String LONG_ASSISTANT = "好的，根据你这一周的安排，我推测本轮报告的交付时间需要调整到周三，"
            + "并且我会按照你之前确认过的格式先把要点整理成三个部分，再附上上周的对比数据一起发给你复核。"
            + "如果周三之前你还有其他更紧急的事项，也可以随时告诉我，我可以把交付时间再顺延到你觉得合适的日子。";

    @MockitoBean
    ModelRegistry registry;
    @MockitoBean
    ModelGateway modelGateway;

    @Autowired
    ContextFactExtractionService service;
    @Autowired
    ChatMessageRecordRepository messages;
    @Autowired
    ContextFactRecordRepository factRecords;
    @Autowired
    ContextFactService facts;
    @Autowired
    ChatProperties chatProperties;
    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
        messages.deleteAll();
        factRecords.deleteAll();
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void extractedCandidateStoredAsAgentDerivedNeedsRefresh() {
        seedTurn("s1", 1, 2, LONG_ASSISTANT);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenReturn(response(VALID_OUTPUT));

        int captured = service.extract(1L, 1L, "s1", 1, 2);

        assertThat(captured).isEqualTo(1);
        ContextFactRecord record = activeFact("s1", "report.delivery.day");
        assertThat(record.getTrustLevel()).isEqualTo(ContextFactTrustLevel.AGENT_DERIVED);
        assertThat(record.getVerificationState()).isEqualTo(ContextFactVerificationState.NEEDS_REFRESH);
        assertThat(record.getSourceType()).isEqualTo(ContextFactSourceType.CHAT_MESSAGE);
        assertThat(record.getSourceId()).isEqualTo("s1");
        assertThat(record.getSourceVersion()).isEqualTo("turn:2");
        assertThat(record.getEvidenceRefsJson()).contains("\"fromSeq\":1").contains("\"toSeq\":2");
        // 安全锚点：待确认推断不进入模型上下文。
        assertThat(facts.resolveForChat("s1")).isEmpty();
    }

    @Test
    void malformedOrEmptyOutputCapturesNothing() {
        seedTurn("s1", 1, 2, LONG_ASSISTANT);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenReturn(response("这不是 JSON"));

        assertThat(service.extract(1L, 1L, "s1", 1, 2)).isZero();
        when(modelGateway.call(any())).thenReturn(response("{\"facts\":[]}"));
        assertThat(service.extract(1L, 1L, "s1", 1, 2)).isZero();
        assertThat(factRecords.count()).isZero();
    }

    @Test
    void modelFailureIsSwallowedWithoutRetry() {
        seedTurn("s1", 1, 2, LONG_ASSISTANT);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenThrow(new RuntimeException("上游 502"));

        assertThat(service.extract(1L, 1L, "s1", 1, 2)).isZero();
        verify(modelGateway, times(1)).call(any());
        assertThat(factRecords.count()).isZero();
    }

    @Test
    void shortAssistantReplySkipsModelCall() {
        seedTurn("s1", 1, 2, "好的。");

        assertThat(service.extract(1L, 1L, "s1", 1, 2)).isZero();
        verify(modelGateway, never()).call(any());
    }

    @Test
    void invalidCandidateSkippedButOthersCaptured() {
        seedTurn("s1", 1, 2, LONG_ASSISTANT);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        String mixed = "{\"facts\":[{\"factKey\":\"非法键!\",\"factType\":\"DATE_TIME\","
                + "\"valueJson\":\"{}\",\"displaySummary\":\"坏候选\"},"
                + "{\"factKey\":\"report.owner\",\"factType\":\"EXTERNAL_OBJECT\","
                + "\"valueJson\":\"{\\\"id\\\":\\\"doc-9\\\"}\",\"displaySummary\":\"报告属主对象\"}]}";
        when(modelGateway.call(any())).thenReturn(response(mixed));

        assertThat(service.extract(1L, 1L, "s1", 1, 2)).isEqualTo(1);
        assertThat(facts.listActive(ContextFactScopeType.CHAT_SESSION, "s1"))
                .extracting(ContextFactRecord::getFactKey).containsExactly("report.owner");
    }

    @Test
    void duplicateExtractionDoesNotBumpRevision() {
        seedTurn("s1", 1, 2, LONG_ASSISTANT);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenReturn(response(VALID_OUTPUT));

        service.extract(1L, 1L, "s1", 1, 2);
        service.extract(1L, 1L, "s1", 1, 2);

        assertThat(factRecords.count()).isEqualTo(1);
        assertThat(activeFact("s1", "report.delivery.day").getRevision()).isEqualTo(1);
    }

    @Test
    void scheduleTurnExtractsThenThrottlesWithinInterval() {
        seedTurn("s1", 1, 2, LONG_ASSISTANT);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        when(modelGateway.call(any())).thenReturn(response(VALID_OUTPUT));
        ContextFactExtractionService direct = manualService(chatProperties);

        direct.scheduleTurn(1L, 1L, "s1", 1, 2);
        // 节流窗口内的第二轮直接跳过：只发生一次模型调用。
        direct.scheduleTurn(1L, 1L, "s1", 3, 4);

        verify(modelGateway, times(1)).call(any());
        assertThat(factRecords.count()).isEqualTo(1);
    }

    @Test
    void disabledExtractionNeverCallsModel() {
        seedTurn("s1", 1, 2, LONG_ASSISTANT);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(summarySlot());
        ContextFactExtractionService disabled = manualService(
                new ChatProperties(null, null, null, new ChatProperties.Fact(false, 0, 0, null)));

        disabled.scheduleTurn(1L, 1L, "s1", 1, 2);
        disabled.extract(1L, 1L, "s1", 1, 2);

        verify(modelGateway, never()).call(any());
        assertThat(factRecords.count()).isZero();
    }

    /** 直通执行器 + 手工装配：scheduleTurn 的节流/开关语义可同步断言。 */
    private ContextFactExtractionService manualService(ChatProperties properties) {
        return new ContextFactExtractionService(messages, facts, registry, modelGateway, Runnable::run,
                properties, mapper);
    }

    /** 经 service 层读取 ACTIVE 事实（与 ContextFactServiceTest 同款断言路径），避免裸派生查询。 */
    private ContextFactRecord activeFact(String sessionId, String factKey) {
        return facts.listActive(ContextFactScopeType.CHAT_SESSION, sessionId).stream()
                .filter(record -> factKey.equals(record.getFactKey()))
                .findFirst().orElseThrow();
    }

    private void seedTurn(String sessionId, int from, int to, String assistant) {
        List<ChatMessageRecord> rows = List.of(
                new ChatMessageRecord(1L, 1L, sessionId, "USER", "这周报告能不能往后放两天？", from),
                new ChatMessageRecord(1L, 1L, sessionId, "ASSISTANT", assistant, to));
        messages.saveAll(rows);
    }

    private static ModelResponse response(String content) {
        return new ModelResponse(content, "", ModelUsage.empty());
    }

    private static ModelSlot summarySlot() {
        return new ModelSlot("summary", "Summary", "openai", "https://example.test",
                "k", "summary-model", 0.2, true);
    }
}
