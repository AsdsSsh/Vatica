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
 * @param fact    迭代 34：Agent 推断事实的异步后置抽取配置
 */
@ConfigurationProperties(prefix = "vatica.chat")
public record ChatProperties(Sse sse, Memory memory, Summary summary, Fact fact) {

    /** 存在兼容构造器时，绑定必须显式指向规范构造器。 */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
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
        if (fact == null) {
            fact = new Fact(true, 0, 0, null);
        }
    }

    /** 兼容迭代 33 及更早的程序化构造器（无事实抽取配置）。 */
    public ChatProperties(Sse sse, Memory memory, Summary summary) {
        this(sse, memory, summary, null);
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
     * 迭代 34：Agent 推断事实的异步后置抽取。抽取尽力而为——失败不重试、不阻塞聊天，
     * 且入库一律被强制压成 NEEDS_REFRESH，只有用户确认才进入模型上下文。
     */
    public record Fact(boolean enabled, int maxFactsPerTurn, int minAssistantChars, Duration minInterval) {

        public static final int DEFAULT_MAX_FACTS_PER_TURN = 3;
        public static final int DEFAULT_MIN_ASSISTANT_CHARS = 120;
        public static final Duration DEFAULT_MIN_INTERVAL = Duration.ofSeconds(30);

        public Fact {
            if (maxFactsPerTurn <= 0) {
                maxFactsPerTurn = DEFAULT_MAX_FACTS_PER_TURN;
            }
            if (minAssistantChars <= 0) {
                minAssistantChars = DEFAULT_MIN_ASSISTANT_CHARS;
            }
            if (minInterval == null || minInterval.isNegative()) {
                minInterval = DEFAULT_MIN_INTERVAL;
            }
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
