package com.example.vatica.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI 契约配置（迭代 9 I9-2）：{@code GET /v3/api-docs} 为前后端契约的
 * 单一事实来源（前端 api.ts 类型与之逐字段对齐）；Swagger UI 开发期可视化契约。
 *
 * <p>契约内容 = 根路径索引 + 全部 {@code /api/**} 业务接口（13 个）；
 * {@code /mcp} 是 MCP 协议端点（由协议自身描述：initialize / tools/list），
 * 不走 Spring MVC 映射，天然不在业务契约内——不定义 GroupedOpenApi，
 * 保持单一 /v3/api-docs 入口（实测分组会多出 /v3/api-docs/{group} 双入口）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI vaticaOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Vatica API")
                .description("Vatica AI 办公 Agent 平台后端接口契约（前后端分离：纯 API 后端，"
                        + "前端为 Tauri 桌面壳，只通过 HTTP API 交互）")
                .version("1.0"));
    }
}
