package com.example.vatica.weather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

/**
 * 第三方模拟天气 MCP 服务进程（迭代 4）。
 *
 * <p>与主应用共用同一份代码与依赖，但<b>只扫描 weather 包</b>（不加载聊天控制器/本地工具），
 * 激活 {@code weather} profile 后以独立端口（8081）运行，通过 Spring AI MCP Server
 * （Streamable HTTP，/mcp）把 {@link WeatherTools} 暴露给主应用的 MCP Client——
 * 对主应用而言它就是一个"第三方 MCP 服务"（协议真实、数据模拟）。
 *
 * <p>启动方式（backend 目录）：
 * {@code mvn spring-boot:run -Dspring-boot.run.main-class=com.example.vatica.weather.WeatherMcpApplication
 *      -Dspring-boot.run.profiles=weather}
 */
@SpringBootApplication(scanBasePackages = "com.example.vatica.weather")
@Profile("weather")
public class WeatherMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherMcpApplication.class, args);
    }
}
