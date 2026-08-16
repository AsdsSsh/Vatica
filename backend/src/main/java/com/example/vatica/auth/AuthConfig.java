package com.example.vatica.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.vatica.secret.MasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 鉴权装配（迭代 13 I13-2）：密码哈希 / JWT / 拦截器 Bean。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    JwtService jwtService(MasterKeyProvider masterKeyProvider, ObjectMapper objectMapper,
            AuthProperties props) {
        return new JwtService(masterKeyProvider, objectMapper, props.tokenTtl());
    }

    @Bean
    JwtAuthInterceptor jwtAuthInterceptor(AuthProperties props, JwtService jwtService, ObjectMapper objectMapper) {
        return new JwtAuthInterceptor(props, jwtService, objectMapper);
    }
}
