package com.example.vatica.controller;

/**
 * 错误处理公共工具（迭代 9 I9-3）：根因消息提取，供全局异常处理器（500 响应）
 * 与模型连通性测试（迭代 8.5）共用，避免同一逻辑两处漂移。
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    /**
     * 异常链剥到最内层，取用户可读消息；空消息退回异常类名。
     * （如 {@code IllegalStateException("外层包装", new RuntimeException("401 Unauthorized"))}
     * → {@code "401 Unauthorized"}，界面反馈即排查信息。）
     */
    public static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
