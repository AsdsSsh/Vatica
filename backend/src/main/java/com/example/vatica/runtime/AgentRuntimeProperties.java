package com.example.vatica.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 17A：运行时切换配置（{@code vatica.agent.runtime=legacy|agentscope}，默认 agentscope）。 */
@ConfigurationProperties(prefix = "vatica.agent")
public record AgentRuntimeProperties(String runtime) {

    public static final String LEGACY = "legacy";
    public static final String AGENTSCOPE = "agentscope";

    public AgentRuntimeProperties {
        runtime = runtime == null || runtime.isBlank() ? AGENTSCOPE : runtime.trim().toLowerCase();
        if (!LEGACY.equals(runtime) && !AGENTSCOPE.equals(runtime)) {
            throw new IllegalArgumentException("操作失败：vatica.agent.runtime 仅支持 legacy / agentscope。");
        }
    }
}
