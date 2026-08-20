package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.vatica.tool.AgentToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 迭代 22B：从 AgentScope @Tool 生成原生 AgentTool。
 * Spring AI 2.0 与 AgentScope 2.0 对 victools 的二进制版本要求冲突，迁移期间不走
 * Toolkit.registerTool 的内置 schema 生成器；这里读取同一套 AgentScope 注解，避免把 Spring AI 回调带回执行链。
 */
public final class AgentScopeToolProvider implements AgentToolProvider {

    private final int maxCallsPerRequest;
    private final Object[] toolObjects;
    private final ObjectMapper mapper;

    public AgentScopeToolProvider(int maxCallsPerRequest, ObjectMapper mapper, Object... toolObjects) {
        if (maxCallsPerRequest <= 0) {
            throw new IllegalArgumentException("maxCallsPerRequest 必须为正数");
        }
        this.maxCallsPerRequest = maxCallsPerRequest;
        this.mapper = mapper;
        this.toolObjects = toolObjects == null ? new Object[0] : toolObjects.clone();
    }

    @Override
    public AgentTool[] getAgentTools() {
        List<AgentTool> discovered = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            if (toolObject == null) {
                continue;
            }
            for (Method method : toolObject.getClass().getMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null) {
                    discovered.add(new ReflectiveAgentTool(toolObject, method, annotation, mapper));
                }
            }
        }
        AtomicInteger calls = new AtomicInteger();
        List<AgentTool> result = new ArrayList<>();
        for (AgentTool tool : discovered) {
            result.add(new LimitedAgentTool(tool, calls, maxCallsPerRequest));
        }
        return result.toArray(AgentTool[]::new);
    }

    private static final class ReflectiveAgentTool implements AgentTool {
        private final Object target;
        private final Method method;
        private final String name;
        private final String description;
        private final List<ParameterSpec> parameters;
        private final ObjectMapper mapper;

        private ReflectiveAgentTool(Object target, Method method, Tool annotation, ObjectMapper mapper) {
            this.target = target;
            this.method = method;
            this.name = annotation.name().isBlank() ? method.getName() : annotation.name();
            this.description = annotation.description();
            this.mapper = mapper;
            Parameter[] methodParameters = method.getParameters();
            this.parameters = new ArrayList<>(methodParameters.length);
            for (Parameter parameter : methodParameters) {
                ToolParam detail = parameter.getAnnotation(ToolParam.class);
                if (detail == null) {
                    throw new IllegalStateException("操作失败：AgentScope 工具 " + name
                            + " 的参数缺少 @ToolParam 注解。");
                }
                parameters.add(new ParameterSpec(detail.name(), detail.description(), detail.required(), parameter.getType()));
            }
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return description; }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (ParameterSpec parameter : parameters) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", jsonType(parameter.type()));
                schema.put("description", parameter.description());
                properties.put(parameter.name(), schema);
                if (parameter.required()) {
                    required.add(parameter.name());
                }
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return Map.copyOf(schema);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
            return Mono.fromCallable(() -> {
                Object[] args = new Object[parameters.size()];
                Map<String, Object> input = call.getInput() == null ? Map.of() : call.getInput();
                for (int i = 0; i < parameters.size(); i++) {
                    ParameterSpec parameter = parameters.get(i);
                    Object raw = input.get(parameter.name());
                    if (raw == null && parameter.required()) {
                        throw new IllegalArgumentException("操作失败：工具参数 " + parameter.name() + " 不能为空。");
                    }
                    args[i] = raw == null ? null : mapper.convertValue(raw, parameter.type());
                }
                try {
                    Object output = method.invoke(target, args);
                    return ToolResultBlock.text(output == null ? "" : String.valueOf(output));
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new IllegalStateException("操作失败：工具执行失败。", cause);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("操作失败：工具执行失败。", e);
                }
            });
        }

        private static String jsonType(Class<?> type) {
            if (type == Integer.class || type == int.class || type == Long.class || type == long.class) return "integer";
            if (type == Double.class || type == double.class || type == Float.class || type == float.class) return "number";
            if (type == Boolean.class || type == boolean.class) return "boolean";
            return "string";
        }

        private record ParameterSpec(String name, String description, boolean required, Class<?> type) { }
    }

    private record LimitedAgentTool(AgentTool delegate, AtomicInteger calls, int maxCalls) implements AgentTool {
        @Override public String getName() { return delegate.getName(); }
        @Override public String getDescription() { return delegate.getDescription(); }
        @Override public java.util.Map<String, Object> getParameters() { return delegate.getParameters(); }
        @Override public Boolean getStrict() { return delegate.getStrict(); }
        @Override public java.util.Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
        @Override public boolean isReadOnly() { return delegate.isReadOnly(); }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            if (calls.incrementAndGet() > maxCalls) {
                return Mono.just(ToolResultBlock.error("操作失败：本次请求的工具调用次数已达上限（" + maxCalls
                        + " 次）。请停止调用工具，基于已有信息直接给出最终回答；若任务确实无法完成，请说明原因。"));
            }
            return delegate.callAsync(param);
        }
    }
}
