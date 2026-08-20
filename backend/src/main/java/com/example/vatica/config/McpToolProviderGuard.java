package com.example.vatica.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.vatica.tool.AgentToolProvider;

import io.agentscope.core.tool.AgentTool;

/**
 * 迭代 22C：AgentScope MCP 工具发现失败时返回空目录，并按窗口退避。
 */
public final class McpToolProviderGuard implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProviderGuard.class);

    static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(300);

    private final AgentToolProvider delegate;
    private final Duration retryBackoff;
    private final Clock clock;
    private volatile Instant lastFailure;

    public McpToolProviderGuard(AgentToolProvider delegate) {
        this(delegate, DEFAULT_RETRY_BACKOFF, Clock.systemUTC());
    }

    public McpToolProviderGuard(AgentToolProvider delegate, Duration retryBackoff) {
        this(delegate, retryBackoff, Clock.systemUTC());
    }

    McpToolProviderGuard(AgentToolProvider delegate, Duration retryBackoff, Clock clock) {
        this.delegate = delegate;
        this.retryBackoff = retryBackoff == null ? DEFAULT_RETRY_BACKOFF : retryBackoff;
        this.clock = clock;
    }

    /**
     * 迭代 23C：只读取本次进程已知的退避状态，不触发远程 MCP 初始化或工具发现。
     */
    public boolean isRetryBackoffActive() {
        Instant failedAt = lastFailure;
        return failedAt != null && Instant.now(clock).isBefore(failedAt.plus(retryBackoff));
    }

    @Override
    public AgentTool[] getAgentTools() {
        if (isRetryBackoffActive()) {
            return new AgentTool[0];
        }
        try {
            AgentTool[] tools = delegate.getAgentTools();
            lastFailure = null;
            return tools == null ? new AgentTool[0] : tools;
        } catch (RuntimeException e) {
            boolean firstFailure = lastFailure == null;
            lastFailure = Instant.now(clock);
            if (firstFailure) {
                log.warn("MCP 远程工具不可用，本次及 {} 秒内的请求跳过 MCP 工具：{}",
                        retryBackoff.toSeconds(), e.getMessage());
            }
            return new AgentTool[0];
        }
    }
}
