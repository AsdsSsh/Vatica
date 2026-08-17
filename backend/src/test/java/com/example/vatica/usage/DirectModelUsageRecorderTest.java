package com.example.vatica.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.vatica.runtime.AgentRuntime.StepUsage;

/** 迭代 17A：AgentScope 直连模型仍沿用平台配额和 vatica_usage。 */
class DirectModelUsageRecorderTest {

    @AfterEach
    void tearDown() {
        UsageContext.clear();
    }

    @Test
    void platformCallReservesSettlesAndRecordsUsage() {
        UsageRecorder recorder = mock(UsageRecorder.class);
        UsageQuotaService quota = mock(UsageQuotaService.class);
        DirectModelUsageRecorder bridge = new DirectModelUsageRecorder(recorder, quota);
        UsageContext.set(snapshot(true));

        DirectModelUsageRecorder.Reservation reservation = bridge.begin();
        bridge.complete(reservation, new StepUsage(100, 20, 120, 10), 42);

        verify(quota).reserve(7L, 4_000);
        verify(quota).settle(7L, 120, 4_000);
        ArgumentCaptor<UsageRecord> row = ArgumentCaptor.forClass(UsageRecord.class);
        verify(recorder).enqueue(row.capture());
        assertThat(row.getValue().getRequestId()).isEqualTo("req-agentscope");
        assertThat(row.getValue().getTotalTokens()).isEqualTo(120);
        assertThat(row.getValue().getCacheReadTokens()).isEqualTo(10);
        assertThat(row.getValue().getDurationMs()).isEqualTo(42);
    }

    @Test
    void failedCallReleasesReservationWithoutRecordingUsage() {
        UsageRecorder recorder = mock(UsageRecorder.class);
        UsageQuotaService quota = mock(UsageQuotaService.class);
        DirectModelUsageRecorder bridge = new DirectModelUsageRecorder(recorder, quota);
        UsageContext.set(snapshot(true));

        DirectModelUsageRecorder.Reservation reservation = bridge.begin();
        bridge.abort(reservation);

        verify(quota).settle(7L, 0, 4_000);
        verify(recorder, org.mockito.Mockito.never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    private static UsageContext.Snapshot snapshot(boolean platformQuota) {
        return new UsageContext.Snapshot("req-agentscope", "EXECUTOR", 7L, 9L, "deepseek",
                "task-1", 2, "LOW", 4_000, null, platformQuota);
    }
}
