package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;

/** 迭代 15 I15-3：错误分类边界——瞬时错误重试，业务/权限/限额/unknown 不重试。 */
class ErrorClassifierTest {

    @Test
    void networkAndServerErrorsAreRetryable() {
        assertThat(ErrorClassifier.isRetryable(new SocketTimeoutException("read timed out"))).isTrue();
        assertThat(ErrorClassifier.isRetryable(new RuntimeException("HTTP 502 Bad Gateway"))).isTrue();
        assertThat(ErrorClassifier.isRetryable(new RuntimeException("stream reset"))).isTrue();
        assertThat(ErrorClassifier.isRetryable(
                new RuntimeException("wrap", new java.net.ConnectException("connection refused")))).isTrue();
    }

    @Test
    void businessAndPermissionErrorsAreNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(new IllegalArgumentException("操作失败：文件不存在"))).isFalse();
        assertThat(ErrorClassifier.isRetryable(new RuntimeException("用户拒绝授权"))).isFalse();
        assertThat(ErrorClassifier.isRetryable(new RuntimeException("操作失败：工具调用次数已达上限（20 次）"))).isFalse();
    }

    @Test
    void unknownErrorIsConservativeNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(new IllegalStateException("数据库锁冲突"))).isFalse();
    }

    @Test
    void nullErrorIsNotRetryable() {
        assertThat(ErrorClassifier.isRetryable(null)).isFalse();
    }
}
