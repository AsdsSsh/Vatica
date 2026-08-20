package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 迭代 23B：CORS 默认来源、追加来源和通配符安全边界。 */
class CorsPropertiesTest {

    @Test
    void keepsDesktopDefaultsAndAddsConfiguredOrigins() {
        CorsProperties properties = new CorsProperties(List.of("http://localhost:3000, http://127.0.0.1:3000"));

        assertThat(properties.origins()).contains("http://tauri.localhost", "http://127.0.0.1:5173",
                "http://localhost:3000", "http://127.0.0.1:3000");
    }

    @Test
    void rejectsWildcardOrigin() {
        CorsProperties properties = new CorsProperties(List.of("*"));

        assertThatThrownBy(properties::origins).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许使用通配符");
    }
}
