package com.example.vatica.runtime;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.tool.AgentToolProvider;
import com.example.vatica.runtime.AgentRuntime.AdvisoryRequest;
import com.example.vatica.runtime.AgentRuntime.AdvisoryResult;
import com.example.vatica.usage.DirectModelUsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 22D：AgentScope 是唯一运行时，工厂仅负责延迟装配与统一用量记录。 */
@Component
public class AgentRuntimeFactory {

    private static final String AGENTSCOPE_CLASS = "com.example.vatica.agentscope.AgentScopeRuntime";

    private final ModelRegistry registry;
    private final AgentToolProvider vaticaTools;
    private final ObjectMapper mapper;
    private final AgentRegistry agentRegistry;
    private final DirectModelUsageRecorder directUsage;
    private final ContextBudget contextBudget;
    private final AgentScopeContextProperties contextProperties;
    private final ToolDiscoveryService toolDiscovery;
    private volatile AgentRuntime runtime;

    public AgentRuntimeFactory(ModelRegistry registry, AgentToolProvider vaticaTools,
            ObjectMapper mapper,
            AgentRegistry agentRegistry, DirectModelUsageRecorder directUsage) {
        this(registry, vaticaTools, mapper, agentRegistry, directUsage,
                new ContextBudget(0, 0, 0, 0, 0), new AgentScopeContextProperties(true, 0, 0, 0), null);
    }

    @Autowired
    public AgentRuntimeFactory(ModelRegistry registry, AgentToolProvider vaticaTools,
            ObjectMapper mapper,
            AgentRegistry agentRegistry, DirectModelUsageRecorder directUsage,
            ContextBudget contextBudget, AgentScopeContextProperties contextProperties,
            ToolDiscoveryService toolDiscovery) {
        this.registry = registry;
        this.vaticaTools = vaticaTools;
        this.mapper = mapper;
        this.agentRegistry = agentRegistry;
        this.directUsage = directUsage;
        this.contextBudget = contextBudget == null ? new ContextBudget(0, 0, 0, 0, 0) : contextBudget;
        this.contextProperties = contextProperties == null
                ? new AgentScopeContextProperties(true, 0, 0, 0) : contextProperties;
        this.toolDiscovery = toolDiscovery;
    }

    /** 兼容迭代 22D～31D 的程序化构造器。 */
    public AgentRuntimeFactory(ModelRegistry registry, AgentToolProvider vaticaTools,
            ObjectMapper mapper,
            AgentRegistry agentRegistry, DirectModelUsageRecorder directUsage,
            ContextBudget contextBudget, AgentScopeContextProperties contextProperties) {
        this(registry, vaticaTools, mapper, agentRegistry, directUsage,
                contextBudget, contextProperties, null);
    }

    /** 迭代 20C：AgentScope 建议直连模型，统一复用平台配额与 usage 记录。 */
    public Optional<AdvisoryResult> advise(AdvisoryRequest request) {
        AgentRuntime selected = runtime();
        DirectModelUsageRecorder.Reservation reservation = directUsage.begin();
        try {
            Optional<AdvisoryResult> result = selected.advise(request);
            if (result.isPresent()) {
                AdvisoryResult value = result.get();
                directUsage.complete(reservation, value.usage(), value.durationMs());
            } else {
                directUsage.abort(reservation);
            }
            return result;
        } catch (RuntimeException e) {
            directUsage.abort(reservation);
            throw e;
        }
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
        try {
            Class<?> type = Class.forName(AGENTSCOPE_CLASS);
            try {
                // 迭代 32B：把混合工具召回器传入所有 AgentScope 入口。
                return (AgentRuntime) type.getConstructor(ModelRegistry.class, AgentToolProvider.class,
                        ObjectMapper.class, AgentRegistry.class, ContextBudget.class,
                        AgentScopeContextProperties.class, ToolDiscoveryService.class)
                        .newInstance(registry, vaticaTools, mapper, agentRegistry,
                                contextBudget, contextProperties, toolDiscovery);
            } catch (NoSuchMethodException currentRuntime) {
                try {
                    // 迭代 30B：旧运行时接收统一上下文预算配置；继续向后兼容。
                    return (AgentRuntime) type.getConstructor(ModelRegistry.class, AgentToolProvider.class,
                            ObjectMapper.class, AgentRegistry.class, ContextBudget.class,
                            AgentScopeContextProperties.class)
                            .newInstance(registry, vaticaTools, mapper, agentRegistry,
                                    contextBudget, contextProperties);
                } catch (NoSuchMethodException legacyRuntime) {
                    return (AgentRuntime) type.getConstructor(ModelRegistry.class, AgentToolProvider.class,
                            ObjectMapper.class, AgentRegistry.class)
                            .newInstance(registry, vaticaTools, mapper, agentRegistry);
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("操作失败：AgentScopeRuntime 未进入当前构建，请使用默认 Maven 构建重新打包。", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("操作失败：AgentScopeRuntime 初始化失败。", e);
        }
    }
}
