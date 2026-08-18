package com.example.vatica.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.task.TaskRecord;
import com.example.vatica.task.TaskRecordRepository;
import com.example.vatica.task.TaskStatus;
import com.example.vatica.task.TaskVerdict;
import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 18B：运行时基线必须按用户聚合 token 与工具调用，不跨租户污染。 */
class UsageServiceReliabilityTest {

    private final UsageRecordRepository usage = mock(UsageRecordRepository.class);
    private final UsageQuotaService quota = mock(UsageQuotaService.class);
    private final TaskRecordRepository tasks = mock(TaskRecordRepository.class);
    private final AgentTraceRecordRepository traces = mock(AgentTraceRecordRepository.class);
    private final UsageService service = new UsageService(usage, quota, tasks, traces, new ObjectMapper());

    @Test
    void aggregatesTokensAndToolsByRuntimeForCurrentUserOnly() {
        TaskRecord task = new TaskRecord("task-1", 7L, 9L, "目标", TaskStatus.DONE, "{}", -1, null);
        task.setExecutionRuntime("agentscope");
        task.setExecutionAttempt(2);
        task.setVerdict(TaskVerdict.PASS);
        task.setScore(90);
        Instant started = Instant.parse("2026-08-18T00:00:00Z");
        task.setExecutionStartedAt(started);
        task.setExecutionFinishedAt(started.plusSeconds(2));
        when(tasks.findByUserId(7L)).thenReturn(List.of(task));
        when(usage.findTaskUsageByUserId(7L)).thenReturn(List.of(
                usage("u1", "task-1", 10, 20, 30), usage("u2", "task-1", 3, 4, 7)));
        when(traces.findTaskTracesByUserId(7L)).thenReturn(List.of(
                trace("trace-1", AgentTraceRecord.STATUS_SUCCESS),
                trace("trace-2", AgentTraceRecord.STATUS_FAILED)));

        UsageService.RuntimeTotals result = service.reliability(new RequestIdentity(7L, 9L, "LOCAL", "tester"))
                .runtimes().getFirst();

        assertThat(result.runtime()).isEqualTo("agentscope");
        assertThat(result.totalTokens()).isEqualTo(37);
        assertThat(result.inputTokens()).isEqualTo(13);
        assertThat(result.outputTokens()).isEqualTo(24);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.failedToolCalls()).isEqualTo(1);
        assertThat(result.multiAttemptTasks()).isOne();
        assertThat(result.averageDurationMs()).isEqualTo(2000.0);
    }

    private static UsageRecord usage(String id, String taskId, int input, int output, int total) {
        return new UsageRecord(id, "request-" + id, 7L, 9L, "EXECUTOR", "slot", taskId, 1, "LOW",
                "general", "General", null, input, output, total, 0, 0, 0, null, 100, 0);
    }

    private static AgentTraceRecord trace(String id, String status) {
        return new AgentTraceRecord(id, 7L, 9L, "task-1", 1, "trace-" + id,
                "general", "General", "read_file", "{}", "ok", 2, 10, status, null);
    }
}
