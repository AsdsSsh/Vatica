package com.example.vatica.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.example.vatica.config.McpToolProviderGuard;
import com.example.vatica.agentscope.AgentToolAdapters;
import com.example.vatica.tool.AgentToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.AgentTool;

/** 迭代 17A：任务 Agent 的统一工具目录，本地工具与可用 MCP 工具在角色门禁前合并。 */
@Component
public class AgentToolCatalog {

    private final AgentToolProvider localTools;
    private final ToolCallbackProvider remoteTools;

    private final ObjectMapper mapper;

    public AgentToolCatalog(@Qualifier("vaticaTools") AgentToolProvider localTools,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpTools, ObjectMapper mapper) {
        this.localTools = localTools;
        this.mapper = mapper;
        SyncMcpToolCallbackProvider provider = mcpTools.getIfAvailable();
        this.remoteTools = provider == null ? () -> new ToolCallback[0] : new McpToolProviderGuard(provider);
    }

    /** 按工具名去重，本地定义优先，避免请求级工具 schema 冲突。 */
    public AgentTool[] tools() {
        Map<String, AgentTool> merged = new LinkedHashMap<>();
        add(merged, localTools.getAgentTools());
        add(merged, AgentToolAdapters.fromProvider(remoteTools, mapper));
        return merged.values().toArray(AgentTool[]::new);
    }

    private static void add(Map<String, AgentTool> merged, AgentTool[] tools) {
        if (tools == null) {
            return;
        }
        for (AgentTool tool : tools) {
            merged.putIfAbsent(tool.getName(), tool);
        }
    }
}
