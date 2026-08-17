package com.example.vatica.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 迭代 15 I15-13：用量 advisor——平台槽位预留/结算，用量落 recorder，BYOK 不扣额度。 */
class UsageAdvisorTest {

    @AfterEach
    void tearDown() {
        UsageContext.clear();
    }

    private static UsageContext.Snapshot snapshot(boolean platformQuota) {
        return new UsageContext.Snapshot("req-1", "CHAT", 1L, 1L, "deepseek", null, null,
                "DISABLED", 16_000, 43, platformQuota);
    }

    @Test
    void afterReadsUsageAndEnqueuesRecord() {
        UsageRecorder recorder = mock(UsageRecorder.class);
        UsageQuotaService quota = mock(UsageQuotaService.class);
        UsageAdvisor advisor = new UsageAdvisor(recorder, quota);

        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);
        when(usage.getTotalTokens()).thenReturn(150);
        when(usage.getCacheReadInputTokens()).thenReturn(20L);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("回复"))))
                .metadata(ChatResponseMetadata.builder().usage(usage).build())
                .build();
        UsageContext.set(snapshot(false));

        ChatClientResponse response = advisor.after(new ChatClientResponse(chatResponse, Map.of()), null);

        assertThat(response.chatResponse()).isSameAs(chatResponse);
        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(recorder).enqueue(captor.capture());
        UsageRecord row = captor.getValue();
        assertThat(row.getRequestId()).isEqualTo("req-1");
        assertThat(row.getInputTokens()).isEqualTo(100);
        assertThat(row.getOutputTokens()).isEqualTo(50);
        assertThat(row.getTotalTokens()).isEqualTo(150);
        assertThat(row.getCacheReadTokens()).isEqualTo(20);
        assertThat(row.getContextFillRatio()).isEqualTo(43);
        verify(quota, org.mockito.Mockito.never()).reserve(org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void platformQuotaReservesBeforeAndSettlesAfter() {
        UsageRecorder recorder = mock(UsageRecorder.class);
        UsageQuotaService quota = mock(UsageQuotaService.class);
        UsageAdvisor advisor = new UsageAdvisor(recorder, quota);
        UsageContext.set(snapshot(true));

        advisor.before(null, null);

        verify(quota).reserve(1L, 16_000);
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(80);
        when(usage.getCompletionTokens()).thenReturn(20);
        when(usage.getTotalTokens()).thenReturn(100);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("回复"))))
                .metadata(ChatResponseMetadata.builder().usage(usage).build())
                .build();
        advisor.after(new ChatClientResponse(chatResponse, Map.of()), null);

        verify(quota).settle(1L, 100, 16_000);
    }
}
