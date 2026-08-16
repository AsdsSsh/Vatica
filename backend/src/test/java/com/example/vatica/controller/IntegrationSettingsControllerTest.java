package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.IntegrationSettings;
import com.example.vatica.config.IntegrationSettingsService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;

/** 迭代 13.5：外部服务设置的平台管理员守卫。 */
@ExtendWith(MockitoExtension.class)
class IntegrationSettingsControllerTest {

    @Mock
    IntegrationSettingsService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new IntegrationSettingsController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CharacterEncodingFilter(StandardCharsets.UTF_8.name(), true))
                .build();
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "local"));
    }

    @AfterEach
    void tearDown() {
        RequestIdentityContext.clear();
    }

    @Test
    void localIdentityCanReadMaskedSettings() throws Exception {
        when(service.load()).thenReturn(IntegrationSettings.defaults());

        mockMvc.perform(get("/api/settings/integrations"))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).contains("\"amapKeySet\":false").contains("\"dbMode\":\"MYSQL\"");
                });
    }

    @Test
    void normalUserGetsForbidden() throws Exception {
        RequestIdentityContext.set(new RequestIdentity(2L, 1L, "USER", "alice"));

        mockMvc.perform(get("/api/settings/integrations"))
                .andExpect(status().isForbidden());
    }
}
