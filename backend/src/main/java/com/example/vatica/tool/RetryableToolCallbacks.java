package com.example.vatica.tool;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * Self-Refine 工具重试装饰器（迭代 15 I15-3）：
 * 捕获 RuntimeException 并交给 {@link ErrorClassifier}——retryable 错误在 300-600ms 抖动退避后
 * 重试 1 次，仍失败抛原异常；non-retryable / unknown 直接上抛；Error 直接上抛。
 *
 * <p>重试位于 ToolCallLimitProvider 外层：一次重试会消耗 2 次工具调用额度（故意设计，防重试风暴）。
 */
public final class RetryableToolCallbacks {

    private final LongConsumer sleeper;

    public RetryableToolCallbacks() {
        this(RetryableToolCallbacks::sleepJittered);
    }

    /** 测试注入用：真实实现睡 300-600ms，单测注入 no-op 避免拖慢。 */
    RetryableToolCallbacks(LongConsumer sleeper) {
        this.sleeper = sleeper;
    }

    public ToolCallback[] wrap(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks).map(this::wrapOne).toArray(ToolCallback[]::new);
    }

    /** 迭代 22B：AgentScope 原生异步工具重试一次，沿用原错误分类和抖动退避。 */
    public AgentTool[] wrap(AgentTool[] tools) {
        return Arrays.stream(tools == null ? new AgentTool[0] : tools)
                .map(this::wrapOne).toArray(AgentTool[]::new);
    }

    private AgentTool wrapOne(AgentTool delegate) {
        return new AgentTool() {
            @Override public String getName() { return delegate.getName(); }
            @Override public String getDescription() { return delegate.getDescription(); }
            @Override public java.util.Map<String, Object> getParameters() { return delegate.getParameters(); }
            @Override public Boolean getStrict() { return delegate.getStrict(); }
            @Override public java.util.Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
            @Override public boolean isReadOnly() { return delegate.isReadOnly(); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return delegate.callAsync(param).onErrorResume(error -> {
                    if (!(error instanceof RuntimeException runtime) || !ErrorClassifier.isRetryable(runtime)) {
                        return Mono.error(error);
                    }
                    sleeper.accept(jitterMs());
                    return delegate.callAsync(param);
                });
            }
        };
    }

    private ToolCallback wrapOne(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                try {
                    return delegate.call(toolInput);
                } catch (RuntimeException e) {
                    if (!ErrorClassifier.isRetryable(e)) {
                        throw e;
                    }
                    sleeper.accept(jitterMs());
                    return delegate.call(toolInput);   // 只重试 1 次；仍失败自然抛原异常
                }
            }
        };
    }

    private static long jitterMs() {
        return 300 + ThreadLocalRandom.current().nextLong(301);
    }

    private static void sleepJittered(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("操作失败：工具重试等待被打断。", e);
        }
    }
}
