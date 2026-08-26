package com.example.vatica.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对话层配置（{@code vatica.chat.*}，迭代 2.5 新增）。
 *
 * <p>沿用 {@code vatica.*} 业务自定义前缀，避免与第三方框架配置混淆。
 *
 * @param sse    SSE 流式配置
 * @param memory  会话短期记忆（内存版）配置
 * @param summary 中期滚动摘要的有界补偿配置
 */
@ConfigurationProperties(prefix = "vatica.chat")
public record ChatProperties(Sse sse, Memory memory, Summary summary) {

    public ChatProperties {
        if (sse == null) {
            sse = new Sse(null);
        }
        if (memory == null) {
            memory = new Memory(0, 0, 0, 0);
        }
        if (summary == null) {
            summary = new Summary(0, -1, null);
        }
    }

    /** SSE 流式：超时保护（0/负数回退默认值；"不自动超时"已废弃——挂起连接就是泄漏）。 */
    public record Sse(Duration timeout) {

        public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

        public Sse {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                timeout = DEFAULT_TIMEOUT;
            }
        }
    }

    /** 会话短期记忆：滑动窗口上限与会话数上限（防内存无限增长）。
     *  maxChars 为单会话历史字符数上限（token 的工程近似：中文约 1 字 ≈ 1 token）。 */
    public record Memory(int maxMessages, int maxSessions, int maxChars, int longContextMaxMessages) {

        public static final int DEFAULT_MAX_MESSAGES = 20;
        public static final int DEFAULT_MAX_SESSIONS = 64;
        public static final int DEFAULT_MAX_CHARS = 16000;
        public static final int DEFAULT_LONG_CONTEXT_MAX_MESSAGES = 512;

        /** 兼容迭代 30 及更早的程序化构造器。 */
        public Memory(int maxMessages, int maxSessions, int maxChars) {
            this(maxMessages, maxSessions, maxChars, 0);
        }

        public Memory {
            if (maxMessages <= 0) {
                maxMessages = DEFAULT_MAX_MESSAGES;
            }
            if (maxSessions <= 0) {
                maxSessions = DEFAULT_MAX_SESSIONS;
            }
            if (maxChars <= 0) {
                maxChars = DEFAULT_MAX_CHARS;
            }
            if (longContextMaxMessages <= 0) {
                longContextMaxMessages = DEFAULT_LONG_CONTEXT_MAX_MESSAGES;
            }
            longContextMaxMessages = Math.max(maxMessages, longContextMaxMessages);
        }
    }

    /**
     * 迭代 29A：摘要任务每次只处理一个受控批次；失败后的自动补偿必须有上限。
     * 0 次自动重试是一个有效的保守配置，负数才回退默认值。
     */
    public record Summary(int maxBatchMessages, int maxAutoRetries, Duration retryInitialBackoff) {

        public static final int DEFAULT_MAX_BATCH_MESSAGES = 20;
        public static final int DEFAULT_MAX_AUTO_RETRIES = 2;
        public static final Duration DEFAULT_RETRY_INITIAL_BACKOFF = Duration.ofSeconds(5);

        public Summary {
            if (maxBatchMessages <= 0) {
                maxBatchMessages = DEFAULT_MAX_BATCH_MESSAGES;
            }
            if (maxAutoRetries < 0) {
                maxAutoRetries = DEFAULT_MAX_AUTO_RETRIES;
            }
            if (retryInitialBackoff == null || retryInitialBackoff.isZero() || retryInitialBackoff.isNegative()) {
                retryInitialBackoff = DEFAULT_RETRY_INITIAL_BACKOFF;
            }
        }
    }
}
