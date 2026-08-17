package com.example.vatica.runtime;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import com.example.vatica.config.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 15 I15-17：运行时工厂——默认 legacy；agentscope 类仅存在于 -Pagentscope 构建。 */
@Component
public class AgentRuntimeFactory {

    private static final String AGENTSCOPE_CLASS = "com.example.vatica.agentscope.AgentScopeRuntime";

    private final ModelRegistry registry;
    private final ToolCallbackProvider vaticaTools;
    private final ObjectMapper mapper;
    private final AgentRuntimeProperties props;

    public AgentRuntimeFactory(ModelRegistry registry, ToolCallbackProvider vaticaTools,
            ObjectMapper mapper, AgentRuntimeProperties props) {
        this.registry = registry;
        this.vaticaTools = vaticaTools;
        this.mapper = mapper;
        this.props = props;
    }

    public AgentRuntime runtime() {
        if (AgentRuntimeProperties.AGENTSCOPE.equals(props.runtime())) {
            try {
                Class<?> type = Class.forName(AGENTSCOPE_CLASS);
                return (AgentRuntime) type.getConstructor(ModelRegistry.class, ToolCallbackProvider.class,
                        ObjectMapper.class).newInstance(registry, vaticaTools, mapper);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "操作失败：AgentScopeRuntime 未构建，请使用 Maven profile `agentscope` 构建后重启。");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("操作失败：AgentScopeRuntime 初始化失败。", e);
            }
        }
        return new LegacyRuntime(registry, vaticaTools, mapper);
    }
}
