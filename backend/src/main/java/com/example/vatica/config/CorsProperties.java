package com.example.vatica.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 23B：CORS 受控来源；默认覆盖桌面壳和常用开发端口，可额外配置开发来源。 */
@ConfigurationProperties(prefix = "vatica.cors")
public record CorsProperties(List<String> allowedOrigins) {

    private static final List<String> DEFAULT_ORIGINS = List.of(
            "http://tauri.localhost",
            "tauri://localhost",
            "http://localhost:1420",
            "http://127.0.0.1:1420",
            "http://localhost:5173",
            "http://127.0.0.1:5173");

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .flatMap(value -> java.util.Arrays.stream(value == null ? new String[0] : value.split(",")))
                .map(value -> value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    /** 默认桌面来源永远保留；配置项只增加来源，不支持用通配符放开所有来源。 */
    public List<String> origins() {
        LinkedHashSet<String> origins = new LinkedHashSet<>(DEFAULT_ORIGINS);
        origins.addAll(allowedOrigins);
        if (origins.stream().anyMatch("*"::equals)) {
            throw new IllegalStateException("操作失败：vatica.cors.allowed-origins 不允许使用通配符。");
        }
        return List.copyOf(new ArrayList<>(origins));
    }
}
