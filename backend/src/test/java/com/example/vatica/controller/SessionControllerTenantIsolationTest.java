package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 31B：会话同步接口必须以组织、用户和会话三元组完成隔离。 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-session-tenant;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class SessionControllerTenantIsolationTest {

    private static final long USER_ID = 7L;
    private static final long ORG_ONE = 11L;
    private static final long ORG_TWO = 12L;
    private static final String SESSION_ID = "shared-session";

    @Autowired
    SessionController controller;
    @Autowired
    ChatSessionRecordRepository sessions;
    @Autowired
    ChatMessageRecordRepository messages;
    @Autowired
    ChatSummarySegmentRecordRepository summarySegments;
    @Autowired
    SessionMemory sessionMemory;

    @BeforeEach
    void setUp() {
        RequestIdentityContext.set(identity(ORG_ONE));
        summarySegments.deleteAll();
        messages.deleteAll();
        sessions.deleteAll();
        seedTenant(ORG_ONE, "组织一会话", "组织一消息");
        seedTenant(ORG_TWO, "组织二会话", "组织二消息");
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void listOnlyReturnsCurrentOrganizationSessions() {
        assertThat(controller.list())
                .extracting(SessionController.SessionSummary::title)
                .containsExactly("组织一会话");

        RequestIdentityContext.set(identity(ORG_TWO));
        assertThat(controller.list())
                .extracting(SessionController.SessionSummary::title)
                .containsExactly("组织二会话");
    }

    @Test
    void getOnlyReturnsCurrentOrganizationMessages() {
        SessionController.SessionDetail detail = controller.get(SESSION_ID);

        assertThat(detail.title()).isEqualTo("组织一会话");
        assertThat(detail.messages())
                .extracting(SessionController.SessionMessage::content)
                .containsExactly("组织一消息");

        RequestIdentityContext.set(identity(ORG_TWO));
        SessionController.SessionDetail other = controller.get(SESSION_ID);
        assertThat(other.title()).isEqualTo("组织二会话");
        assertThat(other.messages())
                .extracting(SessionController.SessionMessage::content)
                .containsExactly("组织二消息");
    }

    @Test
    void updateOnlyChangesCurrentOrganizationSession() {
        controller.upsert(SESSION_ID, new SessionController.SessionUpsertRequest("组织一新标题"));

        assertThat(session(ORG_ONE).getTitle()).isEqualTo("组织一新标题");
        assertThat(session(ORG_TWO).getTitle()).isEqualTo("组织二会话");
    }

    @Test
    void deleteOnlyRemovesCurrentOrganizationDataIncludingSummarySegments() {
        assertThat(sessionMemory.history(SESSION_ID)).hasSize(1);

        controller.delete(SESSION_ID);

        assertThat(sessions.findByUserIdAndOrgIdAndSessionId(USER_ID, ORG_ONE, SESSION_ID)).isEmpty();
        assertThat(messages.findByOrgIdAndUserIdAndSessionIdOrderBySeqAsc(
                ORG_ONE, USER_ID, SESSION_ID)).isEmpty();
        assertThat(summarySegments.countByOrgIdAndUserIdAndSessionId(
                ORG_ONE, USER_ID, SESSION_ID)).isZero();
        assertThat(sessionMemory.history(SESSION_ID)).isEmpty();

        assertThat(sessions.findByUserIdAndOrgIdAndSessionId(USER_ID, ORG_TWO, SESSION_ID)).isPresent();
        assertThat(messages.findByOrgIdAndUserIdAndSessionIdOrderBySeqAsc(
                ORG_TWO, USER_ID, SESSION_ID)).singleElement()
                .extracting(ChatMessageRecord::getContent).isEqualTo("组织二消息");
        assertThat(summarySegments.countByOrgIdAndUserIdAndSessionId(
                ORG_TWO, USER_ID, SESSION_ID)).isEqualTo(1);
    }

    private void seedTenant(long orgId, String title, String message) {
        sessions.save(new ChatSessionRecord(USER_ID, orgId, SESSION_ID, title));
        messages.save(new ChatMessageRecord(USER_ID, orgId, SESSION_ID, "USER", message, 1));
        summarySegments.save(new ChatSummarySegmentRecord(
                orgId, USER_ID, SESSION_ID, ChatSummarySegmentLevel.L1_LOCAL,
                1, 1, title + "摘要", 4, 1, "0".repeat(64), "test-v1", "test-model"));
    }

    private ChatSessionRecord session(long orgId) {
        return sessions.findByUserIdAndOrgIdAndSessionId(USER_ID, orgId, SESSION_ID).orElseThrow();
    }

    private static RequestIdentity identity(long orgId) {
        return new RequestIdentity(USER_ID, orgId, "MEMBER", "same-user");
    }
}
