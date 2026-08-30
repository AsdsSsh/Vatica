package com.example.vatica.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/**
 * 迭代 34：确认闭环——confirm 以 USER_CONFIRMED+CURRENT 重新捕获同 key，
 * 复用 supersede/revision 链；撤销后的推断事实永不进入上下文。
 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-fact-confirm;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class ContextFactConfirmTest {

    private static final RequestIdentity ALICE = new RequestIdentity(7L, 3L, "USER", "alice");
    private static final RequestIdentity BOB = new RequestIdentity(8L, 3L, "USER", "bob");

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
    void confirmUpgradesDerivedFactToUserConfirmedCurrent() {
        ContextFactRecord derived = service.capture(derivedRequest());

        ContextFactRecord confirmed = service.confirm(derived.getId(), null);

        assertThat(confirmed.getRevision()).isEqualTo(2);
        assertThat(confirmed.getTrustLevel()).isEqualTo(ContextFactTrustLevel.USER_CONFIRMED);
        assertThat(confirmed.getVerificationState()).isEqualTo(ContextFactVerificationState.CURRENT);
        assertThat(confirmed.getSupersedesFactId()).isEqualTo(derived.getId());
        assertThat(confirmed.getVerifiedAt()).isNotNull();
        assertThat(service.get(derived.getId()).getStatus()).isEqualTo(ContextFactStatus.SUPERSEDED);
        assertThat(service.resolveCurrent(ContextFactScopeType.CHAT_SESSION, "chat-1"))
                .extracting(ContextFactRecord::getId).containsExactly(confirmed.getId());
        assertThat(service.resolveForChat("chat-1"))
                .extracting(ContextFactService.ContextFactSnippet::factKey).containsExactly("delivery.day");
    }

    @Test
    void confirmKeepsDerivedFactOutOfContextUntilConfirmed() {
        ContextFactRecord derived = service.capture(derivedRequest());

        assertThat(derived.getVerificationState()).isEqualTo(ContextFactVerificationState.NEEDS_REFRESH);
        assertThat(service.listActive(ContextFactScopeType.CHAT_SESSION, "chat-1"))
                .extracting(ContextFactRecord::getId).containsExactly(derived.getId());
        assertThat(service.resolveForChat("chat-1")).isEmpty();
    }

    @Test
    void confirmAllowsCorrectedValueAndSummary() {
        ContextFactRecord derived = service.capture(derivedRequest());

        ContextFactRecord confirmed = service.confirm(derived.getId(),
                new ContextFactService.ConfirmRequest("{\"day\":\"周四\"}", "用户确认周四交付"));

        assertThat(confirmed.getValueJson()).contains("周四");
        assertThat(confirmed.getDisplaySummary()).isEqualTo("用户确认周四交付");
        assertThat(confirmed.getObservedAt()).isEqualTo(derived.getObservedAt());
        assertThat(confirmed.getSourceType()).isEqualTo(ContextFactSourceType.CHAT_MESSAGE);
    }

    @Test
    void confirmIsBoundToTenant() {
        ContextFactRecord derived = service.capture(derivedRequest());

        RequestIdentityContext.set(BOB);
        assertThatThrownBy(() -> service.confirm(derived.getId(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无权");

        RequestIdentityContext.set(ALICE);
        assertThat(service.listActive(ContextFactScopeType.CHAT_SESSION, "chat-1"))
                .extracting(ContextFactRecord::getId).containsExactly(derived.getId());
    }

    @Test
    void revokedDerivedFactNeverEntersContext() {
        ContextFactRecord derived = service.capture(derivedRequest());
        ContextFactRecord revoked = service.revoke(derived.getId(), "用户否决推断");

        assertThat(revoked.getStatus()).isEqualTo(ContextFactStatus.REVOKED);
        assertThat(revoked.getVerificationState()).isEqualTo(ContextFactVerificationState.REVOKED);
        assertThat(service.listActive(ContextFactScopeType.CHAT_SESSION, "chat-1")).isEmpty();
        assertThat(service.resolveForChat("chat-1")).isEmpty();
    }

    private static ContextFactService.CaptureRequest derivedRequest() {
        return new ContextFactService.CaptureRequest(ContextFactScopeType.CHAT_SESSION, "chat-1", null, null,
                "delivery.day", ContextFactType.DATE_TIME, "{\"day\":\"周三\"}", "Agent 推断周三交付",
                ContextFactTrustLevel.AGENT_DERIVED, null, ContextFactSourceType.CHAT_MESSAGE, "chat-1",
                "turn:2", null, "[{\"type\":\"chat_span\",\"sessionId\":\"chat-1\",\"fromSeq\":1,\"toSeq\":2}]",
                Instant.parse("2026-08-30T00:00:00Z"), null, null);
    }
}
