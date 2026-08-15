package com.example.vatica.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置（迭代 6 I6-2）：桌面壳与开发期 Vite 的来源放行。
 *
 * <p>来源说明：Tauri 生产环境 WebView 的 origin 是 {@code tauri://localhost}（非 http 协议，
 * 必须显式列入）；开发期 `tauri dev` 跑 Vite dev server（默认 1420，另放 5173 以防端口漂移）。
 * SSE 流式接口用 fetch 读取，同样受 CORS 约束，本配置对全路径生效。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final List<String> ALLOWED_ORIGINS = List.of(
            "tauri://localhost",        // Tauri WebView（生产）
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
