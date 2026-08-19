package com.example.vatica.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.vatica.auth.RequestIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 21A：观测写入只保存脱敏摘要，且结束 Span 不改变业务异常语义。 */
class AgentObservabilityRecorderTest {

    private final AgentSpanRecordRepository repository = mock(AgentSpanRecordRepository.class);
    private final AgentObservabilityRecorder recorder = new AgentObservabilityRecorder(repository, new ObjectMapper());

    @Test
    void startAndFinishPersistSanitizedSpan() {
        AgentObservabilityRecorder.SpanHandle handle = recorder.start(
                new AgentObservabilityRecorder.SpanStart(
                        new RequestIdentity(7L, 9L, "USER", "alice"), "trace-1", "run-1",
                        null, "task-1", 2, 1, "AGENT_STEP", "Executor", "AGENTSCOPE",
                        "research", "Research", "slot-a", "skill-a", "1.0.0",
                        "{\"goal\":\"find\",\"apiKey\":\"secret-value\"}"));
        ArgumentCaptor<AgentSpanRecord> startCaptor = ArgumentCaptor.forClass(AgentSpanRecord.class);
        verify(repository).save(startCaptor.capture());
        assertThat(startCaptor.getValue().getInputSummary()).doesNotContain("secret-value")
                .contains("\"apiKey\":\"***\"");

        when(repository.findById(handle.spanId())).thenReturn(Optional.of(startCaptor.getValue()));
        recorder.finish(handle, AgentObservabilityRecorder.SpanFinish.success(
                "{\"token\":\"hidden\",\"answer\":\"done\"}"), System.nanoTime() - 1_000_000);

        ArgumentCaptor<AgentSpanRecord> finishCaptor = ArgumentCaptor.forClass(AgentSpanRecord.class);
        verify(repository, org.mockito.Mockito.times(2)).save(finishCaptor.capture());
        AgentSpanRecord finished = finishCaptor.getAllValues().get(1);
        assertThat(finished.getStatus()).isEqualTo(AgentSpanRecord.STATUS_SUCCESS);
        assertThat(finished.getOutputSummary()).doesNotContain("hidden").contains("\"token\":\"***\"");
        assertThat(finished.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void persistenceFailureIsCountedAndDoesNotEscape() {
        when(repository.save(any())).thenThrow(new IllegalStateException("database offline"));

        AgentObservabilityRecorder.SpanHandle handle = recorder.start(
                new AgentObservabilityRecorder.SpanStart(
                        new RequestIdentity(1L, 1L, "USER", "alice"), "trace-2", "run-2",
                        null, "task-2", null, 1, "TASK_RUN", "Run", null,
                        null, null, null, null, null, "safe"));

        assertThat(handle).isNotNull();
        assertThat(recorder.droppedCount()).isEqualTo(1);
    }
}
