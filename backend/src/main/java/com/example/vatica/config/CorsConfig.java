package com.example.vatica.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置（迭代 6 I6-2；迭代 8 补 Windows 打包版 origin）：桌面壳与开发期 Vite 的来源放行。
 *
 * <p>来源说明：Tauri WebView 的 origin 按平台不同——
 * <b>Windows 是 {@code http://tauri.localhost}</b>（Tauri 2 标准协议形态，迭代 8 打包版实测
 * 403 定位，开发模式走 Vite 1420 所以迭代 6-7 未暴露），macOS/Linux 是 {@code tauri://localhost}
 * （非 http 协议，必须显式列入）；开发期 `tauri dev` 跑 Vite dev server（默认 1420，另放 5173
 * 以防端口漂移）。SSE 流式接口用 fetch 读取，同样受 CORS 约束，本配置对全路径生效。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final List<String> ALLOWED_ORIGINS = List.of(
            "http://tauri.localhost",   // Tauri WebView（Windows 打包版，实测形态）
            "tauri://localhost",        // Tauri WebView（macOS/Linux）
            "http://localhost:1420",    // Tauri dev（Vite 默认端口）
            "http://localhost:5173");   // 独立 Vite dev

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(ALLOWED_ORIGINS.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
