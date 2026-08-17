package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.ModelRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 迭代 15 I15-9：中期滚动摘要——成功推进水位线；失败不推进（下次自然重试）。
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-summary;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class SessionSummaryServiceTest {

    @MockitoBean
    ModelRegistry registry;

    @Autowired
    SessionSummaryService summaryService;
    @Autowired
    ChatSessionRecordRepository sessions;
    @Autowired
    ChatMessageRecordRepository messages;

    @BeforeEach
    void setUp() {
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
        sessions.deleteAll();
        messages.deleteAll();
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
        ChatClient client = mockClient("用户偏好：周报周三交付");
        when(registry.summarizerClient()).thenReturn(client);

        summaryService.summarize(1L, 1L, "s1", 5);

        ChatSessionRecord session = sessions.findByUserIdAndSessionId(1L, "s1").orElseThrow();
        assertThat(session.getSummaryText()).contains("周报周三交付");
        assertThat(session.getSummaryThroughSeq()).isEqualTo(5);
        assertThat(session.getSummaryTokens()).isGreaterThan(0);
    }

    @Test
    void failedSummaryDoesNotAdvanceWatermark() {
        seedMessages("s1", 1, 10);
        ChatClient client = mock(org.springframework.ai.chat.client.ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(registry.summarizerClient()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenThrow(new RuntimeException("上游 401"));

        summaryService.summarize(1L, 1L, "s1", 5);

        ChatSessionRecord session = sessions.findByUserIdAndSessionId(1L, "s1").orElseThrow();
        assertThat(session.getSummaryThroughSeq()).isZero();
        assertThat(session.getSummaryText()).isNull();
    }

    private static ChatClient mockClient(String summary) {
        ChatClient client = mock(org.springframework.ai.chat.client.ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn(summary);
        return client;
    }
}
