package com.example.vatica.agentscope;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.example.vatica.auth.JwtAuthInterceptor;
import com.example.vatica.auth.McpAuthFilter;
import com.example.vatica.config.McpProperties;
import com.example.vatica.config.McpToolProviderGuard;
import com.example.vatica.tool.AgentToolProvider;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/** 迭代 22C：AgentScope MCP Client 与官方 MCP Java SDK Server 装配。 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class AgentScopeMcpConfig {

    @Bean(name = "rawRemoteMcpTools", destroyMethod = "close")
    AgentScopeMcpToolProvider rawRemoteMcpTools(McpProperties properties) {
        return new AgentScopeMcpToolProvider(properties.client());
    }

    @Bean(name = "remoteMcpTools")
    AgentToolProvider remoteMcpTools(@Qualifier("rawRemoteMcpTools") AgentToolProvider delegate,
            McpProperties properties) {
        return new McpToolProviderGuard(delegate, properties.client().retryBackoff());
    }

    @Bean(name = "mcpServerTools")
    List<SyncToolSpecification> mcpServerTools(@Qualifier("vaticaTools") AgentToolProvider localTools,
            McpJsonMapper mapper) {
        return Arrays.stream(localTools.getAgentTools())
                .map(tool -> serverTool(tool, localTools, mapper))
                .toList();
    }

    @Bean
    McpJsonMapper mcpJsonMapper() {
        return McpJsonMapper.createDefault();
    }

    @Bean(destroyMethod = "close")
    McpEndpoint mcpEndpoint(McpProperties properties, McpJsonMapper mapper,
            @Qualifier("mcpServerTools") List<SyncToolSpecification> tools) {
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(mapper)
                        .mcpEndpoint("/mcp")
                        .build();
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(properties.server().name(), properties.server().version())
                .instructions(properties.server().instructions())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools)
                .requestTimeout(Duration.ofSeconds(30))
                .immediateExecution(true)
                .build();
        return new McpEndpoint(transport, server);
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(McpEndpoint endpoint) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(endpoint.transport(), "/mcp");
        registration.setName("vaticaMcpStreamableServlet");
        registration.setLoadOnStartup(1);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    FilterRegistrationBean<McpAuthFilter> mcpAuthFilter(JwtAuthInterceptor interceptor) {
        FilterRegistrationBean<McpAuthFilter> registration =
                new FilterRegistrationBean<>(new McpAuthFilter(interceptor));
        registration.setName("vaticaMcpAuthFilter");
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    private static SyncToolSpecification serverTool(AgentTool definition, AgentToolProvider localTools,
            McpJsonMapper mapper) {
        try {
            String inputSchema = mapper.writeValueAsString(definition.getParameters());
            McpSchema.Tool schema = McpSchema.Tool.builder()
                    .name(definition.getName())
                    .description(definition.getDescription())
                    .inputSchema(mapper, inputSchema)
                    .build();
            return new SyncToolSpecification(schema,
                    (exchange, arguments) -> invoke(localTools, definition.getName(), arguments));
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：MCP 工具 Schema 生成失败（" + definition.getName() + "）。", e);
        }
    }

    private static McpSchema.CallToolResult invoke(AgentToolProvider localTools, String name,
            Map<String, Object> arguments) {
        AgentTool tool = Arrays.stream(localTools.getAgentTools())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElse(null);
        if (tool == null) {
            return new McpSchema.CallToolResult("操作失败：未知工具（" + name + "）。", true);
        }
        try {
            Map<String, Object> input = arguments == null ? Map.of() : arguments;
            ToolUseBlock use = ToolUseBlock.builder()
                    .id("mcp-" + UUID.randomUUID())
                    .name(name)
                    .input(input)
                    .content("")
                    .build();
            ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                    .toolUseBlock(use)
                    .input(input)
                    .build()).block(Duration.ofSeconds(30));
            if (result == null) {
                return new McpSchema.CallToolResult("操作失败：工具没有返回结果。", true);
            }
            String text = result.getOutput().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining("\n"));
            boolean error = result.getState() != null && result.getState() != ToolResultState.SUCCESS;
            return new McpSchema.CallToolResult(text, error);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "工具执行失败。" : e.getMessage();
            return new McpSchema.CallToolResult(message, true);
        }
    }

    record McpEndpoint(HttpServletStreamableServerTransportProvider transport, McpSyncServer server)
            implements AutoCloseable {
        @Override
        public void close() {
            server.close();
        }
    }
}
