package com.example.vatica.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 15 I15-1：trace 装饰器——聊天只发 SSE，任务落 agent_trace（脱敏摘要级）。 */
class TracedToolCallbacksTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static TraceContext.Snapshot chatTrace() {
        return new TraceContext.Snapshot("trace-1", "chat:1:1:s1", null, null, 1L, 1L, false);
    }

    private static TraceContext.Snapshot taskTrace() {
        return new TraceContext.Snapshot("trace-2", "task:1:1:t1", "t1", 3, 1L, 1L, true);
    }

    private static ToolCallback delegate(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name(name).description("d").inputSchema("{}").build());
        when(callback.call(any())).thenReturn(result);
        return callback;
    }

    @Test
    void chatTraceEmitsSseEventsAndDoesNotPersist() throws Exception {
        ToolCallback delegate = delegate("read_file", "文件内容");
        SseEmitter emitter = mock(SseEmitter.class);
        AgentTraceRecordRepository repository = mock(AgentTraceRecordRepository.class);

        ToolCallback wrapped = new TracedToolCallbacks(mapper, emitter, repository)
                .wrap(new ToolCallback[] { delegate }, chatTrace())[0];
        String out = wrapped.call("{\"path\":\"/a.txt\",\"apiKey\":\"sk-secret\"}");

        assertThat(out).isEqualTo("文件内容");
        verify(emitter, atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(repository, never()).save(any());
    }

    @Test
    void taskTracePersistsSanitizedSuccessRecord() {
        ToolCallback delegate = delegate("mail_query", "收件箱 3 封未读");
        AgentTraceRecordRepository repository = mock(AgentTraceRecordRepository.class);

        ToolCallback wrapped = new TracedToolCallbacks(mapper, repository)
                .wrap(new ToolCallback[] { delegate }, taskTrace())[0];
        wrapped.call("{\"username\":\"a@b.com\",\"password\":\"p-secret\"}");

        ArgumentCaptor<AgentTraceRecord> captor = ArgumentCaptor.forClass(AgentTraceRecord.class);
        verify(repository).save(captor.capture());
        AgentTraceRecord saved = captor.getValue();
        assertThat(saved.getTaskId()).isEqualTo("t1");
        assertThat(saved.getStepId()).isEqualTo(3);
        assertThat(saved.getTraceId()).isEqualTo("trace-2");
        assertThat(saved.getToolName()).isEqualTo("mail_query");
        assertThat(saved.getStatus()).isEqualTo(AgentTraceRecord.STATUS_SUCCESS);
        assertThat(saved.getInputSummary()).doesNotContain("p-secret").contains("\"password\":\"***\"");
        assertThat(saved.getOutputSummary()).isEqualTo("收件箱 3 封未读");
        assertThat(saved.getOutputLength()).isGreaterThan(0);
        assertThat(saved.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void failedToolCallPersistsFailureAndRethrows() {
        ToolCallback delegate = delegate("mail_send", null);
        when(delegate.call(any())).thenThrow(new RuntimeException("SMTP 超时"));
        AgentTraceRecordRepository repository = mock(AgentTraceRecordRepository.class);

        ToolCallback wrapped = new TracedToolCallbacks(mapper, repository)
                .wrap(new ToolCallback[] { delegate }, taskTrace())[0];

        assertThatThrownBy(() -> wrapped.call("{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SMTP 超时");
        ArgumentCaptor<AgentTraceRecord> captor = ArgumentCaptor.forClass(AgentTraceRecord.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AgentTraceRecord.STATUS_FAILED);
        assertThat(captor.getValue().getError()).contains("SMTP 超时");
    }
}
