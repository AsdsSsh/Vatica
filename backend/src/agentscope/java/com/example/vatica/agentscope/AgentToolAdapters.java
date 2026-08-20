package com.example.vatica.agentscope;

import java.util.Arrays;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.tool.AgentTool;

/** 迭代 22B：迁移期只保留一个边界适配器，AgentScope Toolkit 内不再消费 Spring AI 类型。 */
public final class AgentToolAdapters {

    private AgentToolAdapters() {
    }

    public static AgentTool[] fromProvider(ToolCallbackProvider provider, ObjectMapper mapper) {
        return provider == null ? new AgentTool[0] : fromCallbacks(provider.getToolCallbacks(), mapper);
    }

    public static AgentTool[] fromCallbacks(ToolCallback[] callbacks, ObjectMapper mapper) {
        if (callbacks == null) {
            return new AgentTool[0];
        }
        return Arrays.stream(callbacks)
                .map(callback -> new SpringAiToolAdapter(callback, mapper))
                .toArray(AgentTool[]::new);
    }

    public static ToolCallback[] toCallbacks(AgentTool[] tools, ObjectMapper mapper) {
        if (tools == null) {
            return new ToolCallback[0];
        }
        return Arrays.stream(tools)
                .map(tool -> new AgentToolCallbackAdapter(tool, mapper))
                .toArray(ToolCallback[]::new);
    }

    /** AgentScope 工具的临时反向适配，用于复用 Vatica 既有权限/Trace 装饰器。 */
    private static final class AgentToolCallbackAdapter implements ToolCallback {
        private final AgentTool delegate;
        private final ObjectMapper mapper;

        private AgentToolCallbackAdapter(AgentTool delegate, ObjectMapper mapper) {
            this.delegate = delegate;
            this.mapper = mapper;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            try {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(delegate.getName()).description(delegate.getDescription())
                        .inputSchema(mapper.writeValueAsString(delegate.getParameters())).build();
            } catch (Exception e) {
                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name(delegate.getName()).description(delegate.getDescription()).inputSchema("{}").build();
            }
        }

        @Override
        public String call(String toolInput) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> input = mapper.readValue(toolInput, java.util.Map.class);
                io.agentscope.core.message.ToolUseBlock use = io.agentscope.core.message.ToolUseBlock.builder()
                        .id("vatica-adapter")
                        .name(delegate.getName()).input(input).content(toolInput).build();
                io.agentscope.core.message.ToolResultBlock result = delegate.callAsync(
                        io.agentscope.core.tool.ToolCallParam.builder().toolUseBlock(use).input(input).build()).block();
                return result == null || result.getOutput().isEmpty() ? "" : result.getOutput().getFirst().toString();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("操作失败：工具参数 JSON 非法。", e);
            }
        }
    }
}
