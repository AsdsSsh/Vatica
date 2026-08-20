package com.example.vatica.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 22C：AgentScope MCP Client 与官方 SDK Server 的统一配置。 */
@ConfigurationProperties(prefix = "vatica.mcp")
public record McpProperties(Client client, Server server) {

    public McpProperties {
        client = client == null ? new Client(false, null, null, null, Map.of()) : client;
        server = server == null ? new Server("vatica-mcp-server", "1.0.0", null) : server;
    }

    public record Client(boolean enabled, Duration requestTimeout, Duration initializationTimeout,
            Duration retryBackoff, Map<String, Connection> connections) {
        public Client {
            requestTimeout = positiveOr(requestTimeout, Duration.ofSeconds(10));
            initializationTimeout = positiveOr(initializationTimeout, Duration.ofSeconds(10));
            retryBackoff = positiveOr(retryBackoff, Duration.ofSeconds(300));
            connections = connections == null ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(connections));
        }
    }

    public record Connection(boolean enabled, String url, boolean requiresApiKey, String apiKey,
            Map<String, String> headers) {
        public Connection {
            url = url == null ? "" : url.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public record Server(String name, String version, String instructions) {
        public Server {
            name = blankOr(name, "vatica-mcp-server");
            version = blankOr(version, "1.0.0");
            instructions = blankOr(instructions,
                    "Vatica 个人助理工具集：文件读写、文档生成、日历、待办、邮件和知识库检索。");
        }
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
