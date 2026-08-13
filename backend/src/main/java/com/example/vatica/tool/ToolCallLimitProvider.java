package com.example.vatica.tool;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具调用次数护栏（迭代 2.5 I2.5-2，代码审查 R3 修复）。
 *
 * <p>背景（源码核实）：Spring AI 2.0 的 ToolCallingAdvisor 循环<b>没有轮次上限</b>
 * （Builder 无 maxIterations 类选项），模型持续请求工具会死循环烧 token。
 * 本类在 Provider 层兜底：{@link #getToolCallbacks()} 在每次请求构建时恰好被调用一次
 * （DefaultChatClientUtils 构建 ChatClientRequest 时解析 Provider，字节码核实），
 * 因此"每次调用生成新计数器"即天然的<b>按请求隔离</b>。
 *
 * <p>超限后的工具调用返回引导文案（按项目错误约定以"操作失败："开头），
 * 模型读到工具结果后停止循环、基于已有信息作答。
 */
public final class ToolCallLimitProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final int maxCallsPerRequest;

    public ToolCallLimitProvider(ToolCallbackProvider delegate, int maxCallsPerRequest) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        if (maxCallsPerRequest <= 0) {
            throw new IllegalArgumentException("maxCallsPerRequest 必须为正数");
        }
        this.delegate = delegate;
        this.maxCallsPerRequest = maxCallsPerRequest;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        AtomicInteger counter = new AtomicInteger();
        return Arrays.stream(delegate.getToolCallbacks())
                .map(callback -> guarded(callback, counter))
                .toArray(ToolCallback[]::new);
    }

    private ToolCallback guarded(ToolCallback delegate, AtomicInteger counter) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                if (counter.incrementAndGet() > maxCallsPerRequest) {
                    return "操作失败：本次请求的工具调用次数已达上限（" + maxCallsPerRequest
                            + " 次）。请停止调用工具，基于已有信息直接给出最终回答；"
                            + "若任务确实无法完成，请向用户说明并建议缩小任务范围。";
                }
                return delegate.call(toolInput);
            }
        };
    }
}
