package com.example.vatica.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import com.example.vatica.usage.UsageRecord;
import com.example.vatica.usage.UsageRecordRepository;

/** 迭代 18C：固定用例按租户和运行时聚合，覆盖不足与质量失败必须机械判门禁。 */
class EvaluationServiceTest {

    private static final RequestIdentity IDENTITY = new RequestIdentity(7L, 9L, "LOCAL", "tester");

    private final TaskRecordRepository tasks = mock(TaskRecordRepository.class);
    private final UsageRecordRepository usage = mock(UsageRecordRepository.class);
    private final AgentTraceRecordRepository traces = mock(AgentTraceRecordRepository.class);
    private final EvaluationService service = new EvaluationService(new BenchmarkCatalog(),
            new EvaluationProperties(1, 0.8, 70, 0.1), tasks, usage, traces);

    @Test
    void passesAgentScopeWhenEveryCaseMeetsThresholdAndLeavesLegacyPending() {
        List<TaskRecord> rows = List.of(
                task("t1", "document-summary", TaskStatus.DONE, TaskVerdict.PASS, 80),
                task("t2", "weekly-report", TaskStatus.DONE, TaskVerdict.PASS, 82),
                task("t3", "calendar-todo", TaskStatus.DONE, TaskVerdict.PASS, 84),
                task("t4", "permission-boundary", TaskStatus.DONE, TaskVerdict.PASS, 86));
        when(tasks.findByUserId(7L)).thenReturn(rows);
        when(usage.findTaskUsageByUserId(7L)).thenReturn(rows.stream().map(EvaluationServiceTest::usage).toList());
        when(traces.findTaskTracesByUserId(7L)).thenReturn(rows.stream().map(EvaluationServiceTest::trace).toList());

        EvaluationService.EvaluationReport report = service.report(IDENTITY);

        assertThat(report.results()).hasSize(8);
        assertThat(report.gates()).filteredOn(gate -> gate.runtime().equals("agentscope"))
                .singleElement().satisfies(gate -> {
                    assertThat(gate.status()).isEqualTo(EvaluationService.GateStatus.PASS);
                    assertThat(gate.coveredCases()).isEqualTo(4);
                    assertThat(gate.passRate()).isEqualTo(1.0);
                    assertThat(gate.totalTokens()).isEqualTo(40);
                });
        assertThat(report.gates()).filteredOn(gate -> gate.runtime().equals("legacy"))
                .singleElement().satisfies(gate -> {
                    assertThat(gate.status()).isEqualTo(EvaluationService.GateStatus.PENDING);
                    assertThat(gate.reasons()).hasSize(4);
                });
        verify(tasks).findByUserId(7L);
        verify(usage).findTaskUsageByUserId(7L);
        verify(traces).findTaskTracesByUserId(7L);
    }

    @Test
    void failsCoveredRuntimeWhenQualityAndToolRateMissThresholds() {
        List<TaskRecord> rows = List.of(
                task("t1", "document-summary", TaskStatus.NEEDS_REVISION, TaskVerdict.FAIL, 50),
                task("t2", "weekly-report", TaskStatus.NEEDS_REVISION, TaskVerdict.FAIL, 50),
                task("t3", "calendar-todo", TaskStatus.NEEDS_REVISION, TaskVerdict.FAIL, 50),
                task("t4", "permission-boundary", TaskStatus.NEEDS_REVISION, TaskVerdict.FAIL, 50));
        when(tasks.findByUserId(7L)).thenReturn(rows);
        when(usage.findTaskUsageByUserId(7L)).thenReturn(List.of());
        when(traces.findTaskTracesByUserId(7L)).thenReturn(List.of(
                failedTrace("t1"), trace(rows.get(1)), trace(rows.get(2)), trace(rows.get(3))));

        EvaluationService.RuntimeGate gate = service.report(IDENTITY).gates().stream()
                .filter(item -> item.runtime().equals("agentscope"))
                .findFirst().orElseThrow();

        assertThat(gate.status()).isEqualTo(EvaluationService.GateStatus.FAIL);
        assertThat(gate.failedToolRate()).isEqualTo(0.25);
        assertThat(gate.reasons()).anyMatch(reason -> reason.contains("通过率"))
                .anyMatch(reason -> reason.contains("平均评分"))
                .anyMatch(reason -> reason.contains("工具失败率"));
    }

    private static TaskRecord task(String id, String caseId, TaskStatus status, TaskVerdict verdict, int score) {
        TaskRecord task = new TaskRecord(id, 7L, 9L, "goal", status, "{}", -1, null);
        task.setBenchmarkCaseId(caseId);
        task.setExecutionRuntime("agentscope");
        task.setVerdict(verdict);
        task.setScore(score);
        task.setExecutionStartedAt(Instant.parse("2026-08-18T00:00:00Z"));
        task.setExecutionFinishedAt(Instant.parse("2026-08-18T00:00:02Z"));
        return task;
    }

    private static UsageRecord usage(TaskRecord task) {
        return new UsageRecord("u-" + task.getId(), "r-" + task.getId(), 7L, 9L,
                "EXECUTOR", "slot", task.getId(), 1, "LOW", "general", "General", null,
                3, 7, 10, 0, 0, 0, null, 100, 0.01);
    }

    private static AgentTraceRecord trace(TaskRecord task) {
        return trace(task.getId(), AgentTraceRecord.STATUS_SUCCESS);
    }

    private static AgentTraceRecord failedTrace(String taskId) {
        return trace(taskId, AgentTraceRecord.STATUS_FAILED);
    }

    private static AgentTraceRecord trace(String taskId, String status) {
        return new AgentTraceRecord("trace-" + taskId, 7L, 9L, taskId, 1, "request-" + taskId,
                "general", "General", "read_file", "{}", "ok", 2, 10, status, null);
    }
}
