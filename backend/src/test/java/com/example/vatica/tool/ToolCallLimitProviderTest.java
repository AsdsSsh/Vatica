package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 工具调用次数护栏单测（迭代 2.5 I2.5-2）：按请求隔离计数、超限返回引导文案。 */
class ToolCallLimitProviderTest {

    /** 伪工具回调：记录实际执行次数，返回固定结果。 */
    private static ToolCallback fakeCallback(String name, AtomicInteger executions) {
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
                executions.incrementAndGet();
                return name + " 执行结果";
            }
        };
    }

    private static ToolCallLimitProvider provider(AtomicInteger executions, int limit) {
        ToolCallbackProvider delegate = () -> new ToolCallback[] { fakeCallback("fake", executions) };
        return new ToolCallLimitProvider(delegate, limit);
    }

    /** 上限内调用正常透传执行 */
    @Test
    void underLimitPassesThrough() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback[] callbacks = provider(executions, 2).getToolCallbacks();

        assertThat(callbacks[0].call("{}")).isEqualTo("fake 执行结果");
        assertThat(callbacks[0].call("{}")).isEqualTo("fake 执行结果");
        assertThat(executions.get()).isEqualTo(2);
    }

    /** 超限后返回引导文案且不再执行真实工具（防死循环烧 token） */
    @Test
    void overLimitReturnsGuidanceWithoutExecuting() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback[] callbacks = provider(executions, 2).getToolCallbacks();

        callbacks[0].call("{}");
        callbacks[0].call("{}");
        String third = callbacks[0].call("{}");
        String fourth = callbacks[0].call("{}");

        assertThat(third).contains("操作失败").contains("上限").contains("2");
        assertThat(fourth).contains("操作失败").contains("上限");
        assertThat(executions.get()).isEqualTo(2); // 第 3、4 次未透传到真实工具
    }

    /** 每个请求独立计数：新一轮 getToolCallbacks() 计数器归零（源码核实：每请求解析一次 Provider） */
    @Test
    void countersAreIsolatedPerRequest() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallLimitProvider provider = provider(executions, 1);

        ToolCallback[] request1 = provider.getToolCallbacks();
        request1[0].call("{}");
        assertThat(request1[0].call("{}")).contains("上限");

        ToolCallback[] request2 = provider.getToolCallbacks();
        assertThat(request2[0].call("{}")).isEqualTo("fake 执行结果");
        assertThat(executions.get()).isEqualTo(2);
    }

    /** 工具定义原样透传（模型侧看到的工具名与描述不变） */
    @Test
    void toolDefinitionsArePreserved() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback[] callbacks = provider(executions, 2).getToolCallbacks();

        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("fake");
        assertThat(callbacks[0].getToolDefinition().description()).contains("测试工具");
        assertThat(callbacks[0].getToolDefinition().inputSchema()).isEqualTo("{}");
    }

    /** 非法上限拒绝 */
    @Test
    void rejectsInvalidLimit() {
        AtomicInteger executions = new AtomicInteger();
        assertThatThrownBy(() -> new ToolCallLimitProvider(() -> new ToolCallback[] {}, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCallLimitProvider(null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
