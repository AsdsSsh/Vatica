package com.example.vatica.weather;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 天气服务的工具注册（迭代 4）：与主应用 ToolConfig 同款显式注册——
 * MethodToolCallbackProvider 把 {@link WeatherTools} 的 @Tool 方法生成 ToolCallback，
 * MCP Server 的 ToolCallback 转换器据此暴露为 MCP 工具（Streamable HTTP /mcp）。
 */
@Configuration
@Profile("weather")
public class WeatherToolConfig {

    @Bean
    ToolCallbackProvider weatherToolCallbacks(WeatherTools weatherTools) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherTools).build();
    }
}
