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

/** 迭代 17A：任务 Agent 的统一工具目录，本地工具与可用 MCP 工具在角色门禁前合并。 */
@Component
public class AgentToolCatalog {

    private final ToolCallbackProvider localTools;
    private final ToolCallbackProvider remoteTools;

    public AgentToolCatalog(@Qualifier("vaticaTools") ToolCallbackProvider localTools,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpTools) {
        this.localTools = localTools;
        SyncMcpToolCallbackProvider provider = mcpTools.getIfAvailable();
        this.remoteTools = provider == null ? () -> new ToolCallback[0] : new McpToolProviderGuard(provider);
    }

    /** 按工具名去重，本地定义优先，避免请求级工具 schema 冲突。 */
    public ToolCallback[] callbacks() {
        Map<String, ToolCallback> merged = new LinkedHashMap<>();
        add(merged, localTools.getToolCallbacks());
        add(merged, remoteTools.getToolCallbacks());
        return merged.values().toArray(ToolCallback[]::new);
    }

    private static void add(Map<String, ToolCallback> merged, ToolCallback[] callbacks) {
        if (callbacks == null) {
            return;
        }
        for (ToolCallback callback : callbacks) {
            merged.putIfAbsent(callback.getToolDefinition().name(), callback);
        }
    }
}
