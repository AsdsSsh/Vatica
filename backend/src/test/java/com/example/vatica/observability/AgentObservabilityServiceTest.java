package com.example.vatica.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.example.vatica.auth.RequestIdentity;

/** 迭代 21-SEC：traceId 不是授权凭据，查询必须同时携带当前 userId 与 orgId。 */
class AgentObservabilityServiceTest {

    @Test
    void traceQueryUsesBothTenantDimensions() {
        AgentSpanRecordRepository repository = mock(AgentSpanRecordRepository.class);
        AgentObservabilityRecorder recorder = mock(AgentObservabilityRecorder.class);
        AgentObservabilityService service = new AgentObservabilityService(repository, recorder);
        when(repository.findByUserIdAndOrgIdAndTraceIdOrderByStartedAtAscSpanIdAsc(
                17L, 21L, "trace-shared")).thenReturn(List.of());

        assertThat(service.trace(new RequestIdentity(17L, 21L, "MEMBER", "learner"),
                "trace-shared")).isEmpty();

        verify(repository).findByUserIdAndOrgIdAndTraceIdOrderByStartedAtAscSpanIdAsc(
                17L, 21L, "trace-shared");
    }

    @Test
    void queryReturnsRunAggregateAndUsesRepositoryPage() {
        AgentSpanRecordRepository repository = mock(AgentSpanRecordRepository.class);
        AgentObservabilityRecorder recorder = mock(AgentObservabilityRecorder.class);
        AgentObservabilityService service = new AgentObservabilityService(repository, recorder);
        AgentSpanRecord root = new AgentSpanRecord("span-1", "trace-1", null, "run-1", 17L, 21L, "task-1",
                null, 1, "TASK_RUN", "任务运行", "AGENTSCOPE", null, null, "model-1", null, null, "目标摘要");
        root.finish(AgentSpanRecord.STATUS_SUCCESS, Instant.parse("2026-08-22T08:01:00Z"), 1200,
                "完成", null, null, 10, 20, 30, null, null, 0.03d, 92, "PASS");
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<AgentSpanRecord>>any(),
                org.mockito.ArgumentMatchers.<Sort>any())).thenReturn(List.of(root));

        AgentObservabilityService.RunQueryPage page = service.queryRuns(
                new RequestIdentity(17L, 21L, "MEMBER", "learner"),
                new AgentObservabilityService.SpanQuery(null, null, null, null, "SUCCESS", null, null,
                        null, null, null, null, null, null, null, null, null, 0, 20, "durationMs", "asc"));

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalRuns()).isEqualTo(1);
        assertThat(page.aggregate().successCount()).isEqualTo(1);
        assertThat(page.aggregate().totalTokens()).isEqualTo(30);
        assertThat(page.aggregate().totalCost()).isEqualTo(0.03d);
        assertThat(page.sortBy()).isEqualTo("durationMs");
        verify(repository).findAll(org.mockito.ArgumentMatchers.<Specification<AgentSpanRecord>>any(),
                org.mockito.ArgumentMatchers.<Sort>argThat(value -> value.getOrderFor("durationMs") != null));
    }

    @Test
    void queryNormalizesPageSizeAndSortDirection() {
        AgentObservabilityService.SpanQuery query = new AgentObservabilityService.SpanQuery(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                -10L, -5L, 200, -3, 500, "unsupported", "sideways");

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(100);
        assertThat(query.minDurationMs()).isZero();
        assertThat(query.maxDurationMs()).isZero();
        assertThat(query.minJudgeScore()).isEqualTo(100);
        assertThat(query.sortBy()).isEqualTo("startedAt");
        assertThat(query.direction()).isEqualTo("desc");
    }

    @Test
    void diagnosisExplainsRecordedFailureAndRetryFacts() {
        AgentSpanRecordRepository repository = mock(AgentSpanRecordRepository.class);
        AgentObservabilityRecorder recorder = mock(AgentObservabilityRecorder.class);
        AgentObservabilityService service = new AgentObservabilityService(repository, recorder);
        AgentSpanRecord failed = new AgentSpanRecord("span-f", "trace-f", null, "run-f", 17L, 21L, "task-f",
                null, 2, "TOOL", "calendar", "AGENTSCOPE", "planner", "worker", null, "calendar", "1", "摘要");
        failed.finish(AgentSpanRecord.STATUS_FAILED, Instant.parse("2026-08-22T08:01:00Z"), 8_000,
                "失败", "TIMEOUT", "工具超时", 1, 2, 3, null, null, 0.2d, 55, "FAIL");
        when(repository.findAll(org.mockito.ArgumentMatchers.<Specification<AgentSpanRecord>>any(),
                org.mockito.ArgumentMatchers.<Sort>any())).thenReturn(List.of(failed));

        AgentObservabilityService.DiagnosisReport report = service.diagnose(
                new RequestIdentity(17L, 21L, "MEMBER", "learner"),
                new AgentObservabilityService.SpanQuery(null, null, "trace-f", null, null, null, null,
                        null, null, null, null, null, null, null, null, null, 0, 20, "startedAt", "desc"));

        assertThat(report.findings()).extracting(AgentObservabilityService.DiagnosisFinding::kind)
                .contains("FAILURE", "SLOW", "RETRY", "QUALITY");
    }
}
