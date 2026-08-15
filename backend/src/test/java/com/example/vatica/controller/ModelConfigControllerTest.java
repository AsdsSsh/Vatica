package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.vatica.config.ModelConfigService;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;

/** 模型配置接口单测（迭代 8.5）：列表/保存/连通性测试的成功与失败映射。 */
@ExtendWith(MockitoExtension.class)
class ModelConfigControllerTest {

    @Mock
    ModelConfigService config;
    @Mock
    ModelRegistry registry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter(StandardCharsets.UTF_8.name(), true);
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelConfigController(config, registry))
                .addFilters(filter)
                .build();
    }

    @Test
    void listReturnsCurrentSlots() throws Exception {
        when(config.slots()).thenReturn(List.of(new ModelSlot("ds", "DeepSeek",
                ModelSlot.PROTOCOL_OPENAI, "https://api.deepseek.com", "k", "deepseek-v4-flash", 0.7, true)));

        mockMvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).contains("\"id\":\"ds\"").contains("\"protocol\":\"openai\"");
                });
    }

    @Test
    void saveValidatesAndPersists() throws Exception {
        ModelSlot slot = new ModelSlot("ds", "DeepSeek", ModelSlot.PROTOCOL_OPENAI,
                "https://api.deepseek.com", "k", "deepseek-v4-flash", 0.7, true);
        when(config.save(any())).thenReturn(List.of(slot));
        String body = new ObjectMapper().writeValueAsString(List.of(slot));

        mockMvc.perform(put("/api/models").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String resp = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(resp).contains("\"id\":\"ds\"");
                });
    }

    /** 连通性成功：返回 ok + 模型回复。 */
    @Test
    void testConnectionSuccessMapping() throws Exception {
        ModelSlot slot = new ModelSlot("ds", "DeepSeek", ModelSlot.PROTOCOL_OPENAI,
                "https://api.deepseek.com", "k", "deepseek-v4-flash", 0.7, true);
        when(registry.testConnection(any())).thenReturn("正常");
        String body = new ObjectMapper().writeValueAsString(slot);

        mockMvc.perform(post("/api/models/test").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String resp = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(resp).contains("\"ok\":true").contains("\"reply\":\"正常\"");
                });
    }

    /** 连通性失败：异常根因（最内层消息）如实返回给界面。 */
    @Test
    void testConnectionFailureMapsRootCause() throws Exception {
        ModelSlot slot = new ModelSlot("ds", "DeepSeek", ModelSlot.PROTOCOL_OPENAI,
                "https://api.deepseek.com", "wrong", "deepseek-v4-flash", 0.7, true);
        when(registry.testConnection(any())).thenThrow(
                new IllegalStateException("外层包装", new RuntimeException("401 Unauthorized")));
        String body = new ObjectMapper().writeValueAsString(slot);

        mockMvc.perform(post("/api/models/test").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String resp = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(resp).contains("\"ok\":false").contains("401 Unauthorized");
                });
    }
}
