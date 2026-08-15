package com.example.vatica.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 根路径收口（迭代 9 I9-1，后端纯 API 化）：删除迭代 1 的 static/ 验证页后，
 * 后端不再提供任何静态资源——浏览器打开 8080 根路径即见 API 索引（契约入口），
 * 前后端只通过 HTTP API 交互的边界在行为层面也立起来。
 */
@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "vatica");
        info.put("description", "Vatica AI 办公 Agent 平台——纯 API 后端（前后端分离，前端为 Tauri 桌面壳）");
        info.put("openapi", "/v3/api-docs");
        info.put("swaggerUi", "/swagger-ui.html");
        info.put("apiPrefixes", List.of("/api/chat", "/api/task", "/api/files", "/api/models"));
        info.put("mcpEndpoint", "/mcp");
        return info;
    }
}
