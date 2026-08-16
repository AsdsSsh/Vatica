package com.example.vatica.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 迭代 14：JWT 同时保护 REST API 与 MCP Streamable HTTP 入口。 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor interceptor;

    public AuthWebConfig(JwtAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**", "/mcp", "/mcp/**");
    }
}
