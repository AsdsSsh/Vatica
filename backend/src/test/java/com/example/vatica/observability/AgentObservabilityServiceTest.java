package com.example.vatica.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

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
}
