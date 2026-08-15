package com.example.vatica.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS 来源白名单回归测试（迭代 8）：迭代 6 只实测了开发期 origin，打包版 Windows
 * WebView 的真实 origin 是 {@code http://tauri.localhost}（实测 403 定位），
 * 三条关键路径锁死：Windows 打包版放行 / macOS Tauri 协议放行 / 未知来源拒绝。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vatica-cors;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
})
@AutoConfigureMockMvc
class CorsConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void windowsPackagedOriginAllowed() throws Exception {
        mockMvc.perform(options("/api/chat/models")
                        .header(HttpHeaders.ORIGIN, "http://tauri.localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://tauri.localhost"));
    }

    @Test
    void macTauriProtocolOriginAllowed() throws Exception {
        mockMvc.perform(options("/api/chat/models")
                        .header(HttpHeaders.ORIGIN, "tauri://localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "tauri://localhost"));
    }

    @Test
    void unknownOriginRejected() throws Exception {
        mockMvc.perform(options("/api/chat/models")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }
}
