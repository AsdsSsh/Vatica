package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 根路径收口单测（迭代 9 I9-1）：后端纯 API 化后 GET / 返回 API 索引而非静态页面。 */
class RootControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RootController()).build();
    }

    @Test
    void rootReturnsApiIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body)
                            .contains("\"name\":\"vatica\"")
                            .contains("\"openapi\":\"/v3/api-docs\"")
                            .contains("\"swaggerUi\":\"/swagger-ui.html\"")
                            .contains("\"/api/chat\"")
                            .contains("\"/api/task\"")
                            .contains("\"/api/models\"")
                            .contains("\"/api/permissions\"")
                            .contains("\"mcpEndpoint\":\"/mcp\"");
                });
    }
}
