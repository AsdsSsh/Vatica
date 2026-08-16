package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 迭代 12 I12-4：工具活动包装——成功发 start/end，失败发 start/failed 且异常上抛。 */
class ToolActivityCallbacksTest {

    @Test
    void successEmitsStartAndEnd() throws Exception {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name("read_file").description("读文件").inputSchema("{}").build());
        when(delegate.call("{}")).thenReturn("内容");
        SseEmitter emitter = mock(SseEmitter.class);

        ToolCallback wrapped = new ToolActivityCallbacks().wrap(new ToolCallback[] { delegate }, emitter)[0];
        wrapped.call("{}");

        verify(emitter, atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void failureEmitsFailedAndRethrows() throws Exception {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name("mail_send").description("发邮件").inputSchema("{}").build());
        when(delegate.call("{}")).thenThrow(new RuntimeException("SMTP 超时"));
        SseEmitter emitter = mock(SseEmitter.class);

        ToolCallback wrapped = new ToolActivityCallbacks().wrap(new ToolCallback[] { delegate }, emitter)[0];

        assertThatThrownBy(() -> wrapped.call("{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SMTP 超时");
        verify(emitter, atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
