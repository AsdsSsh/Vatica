package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/** MCP 工具提供者韧性包装单测（迭代 8 I8-1）：失败兜底空工具集 + 退避窗口防反复重试。 */
class McpToolProviderGuardTest {

    private static ToolCallback fakeCallback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name)
                        .description("测试工具 " + name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return name + " 执行结果";
            }
        };
    }

    /** 正常路径：透传委托提供者的工具，不吞工具。 */
    @Test
    void delegatesToolCallbacksWhenHealthy() {
        ToolCallback fake = fakeCallback("fake-mcp");
        McpToolProviderGuard guard = new McpToolProviderGuard(() -> new ToolCallback[] { fake });

        assertThat(guard.getToolCallbacks()).containsExactly(fake);
    }

    /** 委托抛异常：返回空工具集（对话不被拖垮），本地工具照常可用。 */
    @Test
    void returnsEmptyToolsetWhenDelegateFails() {
        McpToolProviderGuard guard = new McpToolProviderGuard(() -> {
            throw new IllegalStateException("Client failed to initialize");
        });

        assertThat(guard.getToolCallbacks()).isEmpty();
    }

    /** 退避窗口：失败后短时间内不再触碰委托（避免每次请求都卡第三方超时）。 */
    @Test
    void backsOffWithinWindowAfterFailure() {
        AtomicInteger delegateCalls = new AtomicInteger();
        McpToolProviderGuard guard = new McpToolProviderGuard(() -> {
            delegateCalls.incrementAndGet();
            throw new IllegalStateException("gateway unreachable");
        });

        guard.getToolCallbacks();
        guard.getToolCallbacks();
        guard.getToolCallbacks();

        assertThat(guard.getToolCallbacks()).isEmpty();
        assertThat(delegateCalls.get()).isEqualTo(1);
    }

    /** 委托恢复后：退避窗口解除、恢复透传（退避窗口传 0 即下次调用立即重试）。 */
    @Test
    void recoversAfterDelegateHealthy() {
        AtomicInteger failNext = new AtomicInteger(1);
        ToolCallback fake = fakeCallback("fake-mcp");
        McpToolProviderGuard guard = new McpToolProviderGuard(() -> {
            if (failNext.getAndSet(0) == 1) {
                throw new IllegalStateException("gateway unreachable");
            }
            return new ToolCallback[] { fake };
        }, 0);

        assertThat(guard.getToolCallbacks()).isEmpty();   // 失败 → 兜底
        assertThat(guard.getToolCallbacks()).containsExactly(fake);   // 恢复 → 透传
    }
}
