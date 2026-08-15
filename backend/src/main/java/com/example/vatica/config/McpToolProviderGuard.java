package com.example.vatica.config;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * MCP 工具提供者的韧性包装（迭代 8 I8-1）：
 * {@link org.springframework.ai.mcp.SyncMcpToolCallbackProvider} 在首次取工具列表时才真正
 * 连接第三方 MCP 服务（yml {@code spring.ai.mcp.client.initialized=false} 懒初始化，SDK
 * {@code LifecycleInitializer.withInitialization} 源码核实），连接失败（未配置 key / 网关不可达 /
 * 断网）会以异常形式抛给调用方——若直接暴露，一次失败会拖垮整次对话请求。
 *
 * <p>本包装的职责：失败时返回空工具集（本地工具不受影响，对话照常进行），并退避
 * {@value #RETRY_BACKOFF_SECONDS} 秒——期间不重试、日志只打一次，避免每次请求都卡一次
 * 第三方超时。连接恢复后自动回到透传模式。
 */
public class McpToolProviderGuard implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProviderGuard.class);

    /** 失败退避窗口（秒）：期间直接返回空工具集，不重试第三方连接。 */
    static final long RETRY_BACKOFF_SECONDS = 300;

    private final ToolCallbackProvider delegate;
    private final long retryBackoffSeconds;
    private volatile long lastFailureEpoch = 0;

    public McpToolProviderGuard(ToolCallbackProvider delegate) {
        this(delegate, RETRY_BACKOFF_SECONDS);
    }

    /** @param retryBackoffSeconds 失败退避窗口（秒），测试可传 0 立即恢复 */
    McpToolProviderGuard(ToolCallbackProvider delegate, long retryBackoffSeconds) {
        this.delegate = delegate;
        this.retryBackoffSeconds = retryBackoffSeconds;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        if (lastFailureEpoch > 0
                && Instant.now().getEpochSecond() - lastFailureEpoch < retryBackoffSeconds) {
            return new ToolCallback[0];
        }
        try {
            ToolCallback[] callbacks = delegate.getToolCallbacks();
            lastFailureEpoch = 0;
            return callbacks;
        }
        catch (Exception e) {
            boolean first = lastFailureEpoch == 0;
            lastFailureEpoch = Instant.now().getEpochSecond();
            if (first) {
                log.warn("MCP 远程工具不可用，本次及 {} 秒内的请求跳过 MCP 工具：{}",
                        RETRY_BACKOFF_SECONDS, e.getMessage());
            }
            return new ToolCallback[0];
        }
    }
}
