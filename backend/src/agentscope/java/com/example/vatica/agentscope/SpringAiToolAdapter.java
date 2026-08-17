package com.example.vatica.agentscope;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;

import org.springframework.ai.tool.ToolCallback;

import reactor.core.publisher.Mono;

/**
 * 迭代 15 I15-18：把 Vatica 的 Spring AI ToolCallback 适配为 AgentScope AgentTool。
 * 权限/身份包装仍由 AgentScopeRuntime 在注入前用 PermissionBoundToolCallbacks 完成，
 * 因此 AgentScope 不绕过 Vatica 的权限事实源。
 */
final class SpringAiToolAdapter implements AgentTool {

    private final ToolCallback delegate;
    private final ObjectMapper mapper;

    SpringAiToolAdapter(ToolCallback delegate, ObjectMapper mapper) {
        this.delegate = delegate;
        this.mapper = mapper;
    }

    @Override
    public String getName() {
        return delegate.getToolDefinition().name();
    }

    @Override
    public String getDescription() {
        return delegate.getToolDefinition().description();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getParameters() {
        try {
            return mapper.readValue(delegate.getToolDefinition().inputSchema(), Map.class);
        } catch (Exception e) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            try {
                String input = mapper.writeValueAsString(param.getInput());
                String output = delegate.call(input);
                // 模型必须看到完整工具结果；脱敏摘要只用于外层 trace，不能污染业务数据。
                return ToolResultBlock.text(output == null ? "" : output);
            } catch (RuntimeException e) {
                return ToolResultBlock.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }
}
