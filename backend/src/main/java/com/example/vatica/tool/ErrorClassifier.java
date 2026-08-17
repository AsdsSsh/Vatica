package com.example.vatica.tool;

/**
 * 工具错误分类（迭代 15 I15-3 Self-Refine）：
 * retryable = 网络/超时/HTTP 5xx/429/流重置等瞬时错误；non-retryable = 业务校验、明确语义失败、
 * 用户拒绝授权、工具调用次数上限；unknown 保守不重试（失败如实报告）。
 */
public final class ErrorClassifier {

    private ErrorClassifier() {
    }

    public static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof IllegalArgumentException) {
                // 业务校验/参数错误/用户拒绝授权等明确语义失败，绝不重试
                return false;
            }
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.io.IOException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("429")
                        || lower.contains("timeout")
                        || lower.contains("timed out")
                        || lower.contains("connection")
                        || lower.contains("stream reset")
                        || lower.contains("reset by peer")
                        || lower.contains("503")
                        || lower.contains("502")
                        || lower.contains("504")
                        || lower.contains("500")
                        || lower.contains("temporarily unavailable")) {
                    return true;
                }
                if (lower.contains("次数已达上限") || lower.contains("拒绝授权") || lower.contains("用户拒绝")) {
                    return false;
                }
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;   // unknown：保守不重试
    }
}
