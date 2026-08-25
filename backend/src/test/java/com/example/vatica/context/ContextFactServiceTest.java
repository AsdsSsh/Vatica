package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 29B：H2 验证事实版本、双租户隔离和受控 JSON 边界。 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-context-fact;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class ContextFactServiceTest {

    private static final RequestIdentity ALICE = new RequestIdentity(7L, 3L, "USER", "alice");
    private static final RequestIdentity BOB = new RequestIdentity(8L, 3L, "USER", "bob");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-26T00:00:00Z");

    @Autowired
    private ContextFactService service;

    @Autowired
    private ContextFactRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        RequestIdentityContext.set(ALICE);
    }

    @AfterEach
    void tearDown() {
        RequestIdentityContext.clear();
    }

    @Test
    void captureCreatesRevisionAndSupersedesPreviousValue() {
        ContextFactRecord first = service.capture(request("planning", "周三交付", "m1"));
        ContextFactRecord second = service.capture(request("planning", "周四交付", "m2"));

        assertThat(first.getRevision()).isEqualTo(1);
        assertThat(second.getRevision()).isEqualTo(2);
        assertThat(second.getSupersedesFactId()).isEqualTo(first.getId());
        assertThat(repository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(ContextFactStatus.SUPERSEDED);
        assertThat(repository.findById(first.getId()).orElseThrow().getSupersededByFactId())
                .isEqualTo(second.getId());
        assertThat(service.listActive(ContextFactScopeType.CHAT_SESSION, "chat-1"))
                .extracting(ContextFactRecord::getId).containsExactly(second.getId());
    }

    @Test
    void equivalentCaptureIsIdempotentAndAgentFactsNeedRefresh() {
        ContextFactService.CaptureRequest request = request("approval", "用户已确认", "chat-1");
        ContextFactRecord first = service.capture(request);
        ContextFactRecord repeated = service.capture(request);

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repository.count()).isEqualTo(1);

        ContextFactRecord derived = service.capture(new ContextFactService.CaptureRequest(
                ContextFactScopeType.TASK, "task-1", null, null, "suggested-date", ContextFactType.DATE_TIME,
                "{\"date\":\"2026-08-30\"}", "Agent 建议日期", ContextFactTrustLevel.AGENT_DERIVED, null,
                ContextFactSourceType.TASK_STEP, "task-1:2", "run-1", null, null, OBSERVED_AT, null, null));
        assertThat(derived.getVerificationState()).isEqualTo(ContextFactVerificationState.NEEDS_REFRESH);
        assertThat(service.resolveCurrent(ContextFactScopeType.TASK, "task-1")).isEmpty();
    }

    @Test
    void listAndRevokeAreBoundToBothUserAndOrganization() {
        ContextFactRecord fact = service.capture(request("file", "报告路径已确认", "chat-1"));

        RequestIdentityContext.set(BOB);
        assertThat(service.listActive(ContextFactScopeType.CHAT_SESSION, "chat-1")).isEmpty();
        assertThatThrownBy(() -> service.revoke(fact.getId(), "越权尝试"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无权");

        RequestIdentityContext.set(ALICE);
        ContextFactRecord revoked = service.revoke(fact.getId(), "用户撤销");
        assertThat(revoked.getStatus()).isEqualTo(ContextFactStatus.REVOKED);
        assertThat(revoked.getVerificationState()).isEqualTo(ContextFactVerificationState.REVOKED);
        assertThat(service.listActive(ContextFactScopeType.CHAT_SESSION, "chat-1")).isEmpty();
    }

    @Test
    void canonicalizesSmallJsonAndRejectsRawOrOversizedContent() {
        ContextFactRecord fact = service.capture(new ContextFactService.CaptureRequest(
                ContextFactScopeType.TASK, "task-1", null, null, "external-id", ContextFactType.EXTERNAL_OBJECT,
                "{\"id\":\"x-1\",\"kind\":\"calendar\"}", "已创建日程", ContextFactTrustLevel.TOOL_OBSERVED,
                ContextFactVerificationState.CURRENT, ContextFactSourceType.ACTION_EXECUTION, "action-1", "v1",
                "fp-1", "[{\"type\":\"ACTION_EXECUTION\",\"id\":\"action-1\",\"label\":\"动作结果\"}]",
                OBSERVED_AT, OBSERVED_AT, null));
        assertThat(fact.getValueJson()).isEqualTo("{\"id\":\"x-1\",\"kind\":\"calendar\"}");
        assertThat(fact.getEvidenceRefsJson()).contains("action-1");
        assertThat(fact.getValueHash()).hasSize(64);

        assertThatThrownBy(() -> service.capture(requestWithValue("{\"content\":\"原始工具正文\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("敏感或原始内容");
        String tooLarge = "{\"id\":\"" + "x".repeat(ContextFactService.MAX_VALUE_JSON_CHARS) + "\"}";
        assertThatThrownBy(() -> service.capture(requestWithValue(tooLarge)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能超过");
    }

    @Test
    void deleteScopeOnlyDeletesCurrentTenantFacts() {
        service.capture(request("one", "第一条", "chat-1"));
        RequestIdentityContext.set(BOB);
        service.capture(request("two", "第二条", "chat-1"));

        RequestIdentityContext.set(ALICE);
        assertThat(service.deleteScope(ContextFactScopeType.CHAT_SESSION, "chat-1")).isEqualTo(1);
        assertThat(repository.findByIdAndOrgIdAndUserId("missing", 3L, 7L)).isEmpty();
        RequestIdentityContext.set(BOB);
        assertThat(service.listActive(ContextFactScopeType.CHAT_SESSION, "chat-1")).hasSize(1);
    }

    @Test
    void sourceRefreshStopsFactsFromEnteringChatButKeepsAuditRow() {
        ContextFactRecord fact = service.capture(request("calendar", "会议时间已确认", "event-1"));

        assertThat(service.markNeedsRefreshBySource(ContextFactSourceType.CALENDAR_EVENT, "event-1", "日程已变更"))
                .isZero();
        // 该事实最初来源是 CHAT_MESSAGE，按来源类型+ID 精确失效，不能误伤同 ID 的其他来源。
        assertThat(service.markNeedsRefreshBySource(ContextFactSourceType.CHAT_MESSAGE, "event-1", "消息已修订"))
                .isEqualTo(1);
        assertThat(service.resolveCurrent(ContextFactScopeType.CHAT_SESSION, "chat-1")).isEmpty();
        assertThat(repository.findById(fact.getId()).orElseThrow().getStatus()).isEqualTo(ContextFactStatus.ACTIVE);
    }

    @Test
    void chatResolutionReturnsOnlyShortCurrentSnippets() {
        service.capture(request("confirmed", "用户确认周三交付", "m-1"));
        service.capture(new ContextFactService.CaptureRequest(
                ContextFactScopeType.CHAT_SESSION, "chat-1", null, null, "derived", ContextFactType.DATE_TIME,
                "{\"date\":\"2026-08-30\"}", "Agent 推断周日交付", ContextFactTrustLevel.AGENT_DERIVED, null,
                ContextFactSourceType.TASK_STEP, "step-1", null, null, null, OBSERVED_AT, null, null));

        assertThat(service.resolveForChat("chat-1")).hasSize(1)
                .first().satisfies(snippet -> {
                    assertThat(snippet.factKey()).isEqualTo("confirmed");
                    assertThat(snippet.displaySummary()).doesNotContain("value");
                });
    }

    private static ContextFactService.CaptureRequest request(String key, String summary, String sourceId) {
        return new ContextFactService.CaptureRequest(ContextFactScopeType.CHAT_SESSION, "chat-1", null, null, key,
                ContextFactType.USER_CONFIRMATION, "{\"value\":\"" + summary + "\"}", summary,
                ContextFactTrustLevel.USER_CONFIRMED, ContextFactVerificationState.CURRENT,
                ContextFactSourceType.CHAT_MESSAGE, sourceId, "v1", null, null, OBSERVED_AT, OBSERVED_AT, null);
    }

    private static ContextFactService.CaptureRequest requestWithValue(String valueJson) {
        return new ContextFactService.CaptureRequest(ContextFactScopeType.TASK, "task-1", null, null, "raw",
                ContextFactType.TOOL_OUTCOME, valueJson, "受控摘要", ContextFactTrustLevel.TOOL_OBSERVED,
                ContextFactVerificationState.CURRENT, ContextFactSourceType.TASK_STEP, "task-1:1", "v1", null,
                null, OBSERVED_AT, OBSERVED_AT, null);
    }
}
