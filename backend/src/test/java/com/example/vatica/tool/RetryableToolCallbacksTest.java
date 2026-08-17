package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 迭代 15 I15-3：Self-Refine 工具重试——retryable 恰好重试 1 次；
 * non-retryable/unknown/Error 不重试且原异常上抛。
 */
class RetryableToolCallbacksTest {

    private static ToolCallback delegate(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name(name).description("d").inputSchema("{}").build());
        return callback;
    }

    private static ToolCallback wrapped(ToolCallback delegate) {
        return new RetryableToolCallbacks(ms -> { }).wrap(new ToolCallback[] { delegate })[0];
    }

    @Test
    void retryableErrorRetriesExactlyOnceThenSucceeds() {
        ToolCallback delegate = delegate("read_file");
        when(delegate.call("{}"))
                .thenThrow(new RuntimeException("SocketTimeoutException: Read timed out"))
                .thenReturn("第二次成功");

        String out = wrapped(delegate).call("{}");

        assertThat(out).isEqualTo("第二次成功");
        verify(delegate, times(2)).call("{}");
    }

    @Test
    void nonRetryableBusinessErrorDoesNotRetry() {
        ToolCallback delegate = delegate("write_file");
        when(delegate.call(any())).thenThrow(new IllegalArgumentException("操作失败：路径不合法"));

        assertThatThrownBy(() -> wrapped(delegate).call("{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径不合法");
        verify(delegate, times(1)).call(any());
    }

    @Test
    void toolCallLimitErrorDoesNotRetry() {
        ToolCallback delegate = delegate("read_file");
        when(delegate.call(any())).thenThrow(new RuntimeException("操作失败：本次请求的工具调用次数已达上限（20 次）"));

        assertThatThrownBy(() -> wrapped(delegate).call("{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("次数已达上限");
        verify(delegate, times(1)).call(any());
    }

    @Test
    void unknownErrorIsReportedWithoutRetry() {
        ToolCallback delegate = delegate("calculator");
        when(delegate.call(any())).thenThrow(new IllegalStateException("内部状态异常"));

        assertThatThrownBy(() -> wrapped(delegate).call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内部状态异常");
        verify(delegate, times(1)).call(any());
    }

    @Test
    void retryableErrorThatKeepsFailingRethrowsOriginalAfterOneRetry() {
        ToolCallback delegate = delegate("mail_query");
        when(delegate.call(any())).thenThrow(new RuntimeException("connect timed out"));

        assertThatThrownBy(() -> wrapped(delegate).call("{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connect timed out");
        verify(delegate, times(2)).call(any());
    }

    @Test
    void errorIsRethrownWithoutRetry() {
        ToolCallback delegate = delegate("text_stats");
        when(delegate.call(any())).thenThrow(new OutOfMemoryError("oom"));

        assertThatThrownBy(() -> wrapped(delegate).call("{}"))
                .isInstanceOf(OutOfMemoryError.class);
        verify(delegate, times(1)).call(any());
    }
}
