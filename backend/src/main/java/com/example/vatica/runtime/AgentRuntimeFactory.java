package com.example.vatica.runtime;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.config.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 17A：运行时工厂——默认 AgentScope，保留 legacy 配置作为即时回滚通道。 */
@Component
public class AgentRuntimeFactory {

    private static final String AGENTSCOPE_CLASS = "com.example.vatica.agentscope.AgentScopeRuntime";

    private final ModelRegistry registry;
    private final ToolCallbackProvider vaticaTools;
    private final ObjectMapper mapper;
    private final AgentRuntimeProperties props;
    private final ExecutorAgent executorAgent;
    private final AgentRegistry agentRegistry;
    private volatile AgentRuntime runtime;

    public AgentRuntimeFactory(ModelRegistry registry, ToolCallbackProvider vaticaTools,
            ObjectMapper mapper, AgentRuntimeProperties props, ExecutorAgent executorAgent,
            AgentRegistry agentRegistry) {
        this.registry = registry;
        this.vaticaTools = vaticaTools;
        this.mapper = mapper;
        this.props = props;
        this.executorAgent = executorAgent;
        this.agentRegistry = agentRegistry;
    }

    public AgentRuntime runtime() {
        AgentRuntime existing = runtime;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (runtime == null) {
                runtime = createRuntime();
            }
            return runtime;
        }
    }

    private AgentRuntime createRuntime() {
        if (AgentRuntimeProperties.AGENTSCOPE.equals(props.runtime())) {
            try {
                Class<?> type = Class.forName(AGENTSCOPE_CLASS);
                return (AgentRuntime) type.getConstructor(ModelRegistry.class, ToolCallbackProvider.class,
                        ObjectMapper.class, AgentRegistry.class)
                        .newInstance(registry, vaticaTools, mapper, agentRegistry);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "操作失败：AgentScopeRuntime 未进入当前构建，请使用默认 Maven 构建重新打包，"
                                + "或临时设置 VATICA_AGENT_RUNTIME=legacy 回滚。");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("操作失败：AgentScopeRuntime 初始化失败。", e);
            }
        }
        return new LegacyRuntime(registry, vaticaTools, mapper, executorAgent);
    }
}
