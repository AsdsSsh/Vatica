package com.example.vatica.agentscope;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.vatica.config.McpProperties;
import com.example.vatica.tool.AgentToolProvider;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;

/** 迭代 22C：以 AgentScope McpClientBuilder 懒发现远程 Streamable HTTP 工具。 */
public final class AgentScopeMcpToolProvider implements AgentToolProvider, AutoCloseable {

    private final McpProperties.Client properties;
    private final Map<String, ConnectionState> states = new ConcurrentHashMap<>();

    public AgentScopeMcpToolProvider(McpProperties.Client properties) {
        this.properties = properties;
    }

    @Override
    public AgentTool[] getAgentTools() {
        if (properties == null || !properties.enabled()) {
            return new AgentTool[0];
        }
        Map<String, AgentTool> merged = new LinkedHashMap<>();
        properties.connections().forEach((name, connection) -> {
            if (connection.enabled() && !connection.url().isBlank()
                    && (!connection.requiresApiKey() || !connection.apiKey().isBlank())) {
                for (AgentTool tool : states.computeIfAbsent(name,
                        ignored -> new ConnectionState(name, connection)).tools()) {
                    merged.putIfAbsent(tool.getName(), tool);
                }
            }
        });
        return merged.values().toArray(AgentTool[]::new);
    }

    @Override
    public void close() {
        states.values().forEach(ConnectionState::close);
        states.clear();
    }

    private final class ConnectionState {
        private final String name;
        private final McpProperties.Connection connection;
        private McpClientWrapper client;
        private AgentTool[] cached;

        private ConnectionState(String name, McpProperties.Connection connection) {
            this.name = name;
            this.connection = connection;
        }

        private synchronized AgentTool[] tools() {
            if (cached != null) {
                return cached.clone();
            }
            McpClientBuilder builder = McpClientBuilder.create(name)
                    .streamableHttpTransport(connection.url())
                    .timeout(properties.requestTimeout())
                    .initializationTimeout(properties.initializationTimeout())
                    .headers(connection.headers());
            if (!connection.apiKey().isBlank()) {
                builder.queryParam("key", connection.apiKey());
            }
            McpClientWrapper candidate = builder.buildSync();
            Duration wait = properties.initializationTimeout().plus(properties.requestTimeout());
            try {
                candidate.initialize().block(wait);
                List<McpSchema.Tool> remoteTools = candidate.listTools().block(wait);
                List<AgentTool> discovered = remoteTools == null ? List.of() : remoteTools.stream()
                        .map(tool -> new McpTool(tool.name(), tool.description(),
                                McpTool.convertMcpSchemaToParameters(tool.inputSchema(),
                                        tool.inputSchema().required() == null ? java.util.Set.of()
                                                : java.util.Set.copyOf(tool.inputSchema().required())), candidate))
                        .map(tool -> (AgentTool) tool)
                        .toList();
                client = candidate;
                cached = discovered.toArray(AgentTool[]::new);
                return cached.clone();
            } catch (RuntimeException e) {
                candidate.close();
                throw new IllegalStateException("Client failed to initialize listing tools: " + name, e);
            }
        }

        private synchronized void close() {
            if (client != null) {
                client.close();
                client = null;
            }
            cached = null;
        }
    }
}
