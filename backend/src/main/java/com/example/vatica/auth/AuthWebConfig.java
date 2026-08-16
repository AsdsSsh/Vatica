package com.example.vatica.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 迭代 13 I13-2：把 JWT 拦截器挂到 /api/**（enabled 由 vatica.auth.enabled 控制）。 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor interceptor;

    public AuthWebConfig(JwtAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
