package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.example.vatica.tool.AgentToolProvider;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/** 迭代 22C：AgentScope MCP 工具发现韧性测试。 */
class McpToolProviderGuardTest {

    private static AgentTool fakeTool(String name) {
        return new AgentTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "测试工具 " + name; }
            @Override public Map<String, Object> getParameters() { return Map.of("type", "object"); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text(name + " 执行结果"));
            }
        };
    }

    @Test
    void delegatesAgentToolsWhenHealthy() {
        AgentTool fake = fakeTool("fake-mcp");
        McpToolProviderGuard guard = new McpToolProviderGuard(() -> new AgentTool[] { fake });

        assertThat(guard.getAgentTools()).containsExactly(fake);
    }

    @Test
    void returnsEmptyToolsetWhenDelegateFails() {
        McpToolProviderGuard guard = new McpToolProviderGuard(() -> {
            throw new IllegalStateException("Client failed to initialize listing tools");
        });

        assertThat(guard.getAgentTools()).isEmpty();
    }

    @Test
    void backsOffWithinWindowAfterFailure() {
        AtomicInteger delegateCalls = new AtomicInteger();
        AgentToolProvider delegate = () -> {
            delegateCalls.incrementAndGet();
            throw new IllegalStateException("gateway unreachable");
        };
        McpToolProviderGuard guard = new McpToolProviderGuard(delegate, Duration.ofMinutes(5));

        guard.getAgentTools();
        guard.getAgentTools();
        guard.getAgentTools();

        assertThat(guard.getAgentTools()).isEmpty();
        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    void recoversAfterDelegateHealthy() {
        AtomicInteger failNext = new AtomicInteger(1);
        AgentTool fake = fakeTool("fake-mcp");
        McpToolProviderGuard guard = new McpToolProviderGuard(() -> {
            if (failNext.getAndSet(0) == 1) {
                throw new IllegalStateException("gateway unreachable");
            }
            return new AgentTool[] { fake };
        }, Duration.ZERO);

        assertThat(guard.getAgentTools()).isEmpty();
        assertThat(guard.getAgentTools()).containsExactly(fake);
    }
}
