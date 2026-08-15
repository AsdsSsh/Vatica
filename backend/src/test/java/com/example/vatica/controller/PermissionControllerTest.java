package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.vatica.permission.FilePermissionRequestService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 迭代 11：权限决定接口测试。 */
@ExtendWith(MockitoExtension.class)
class PermissionControllerTest {

    @Mock
    FilePermissionRequestService requestService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PermissionController(requestService)).build();
    }

    @Test
    void approveReturnsOk() throws Exception {
        when(requestService.decide("r1", true, true)).thenReturn(true);

        mockMvc.perform(post("/api/permissions/requests/r1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remember\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void denyReturnsOk() throws Exception {
        when(requestService.decide("r1", false, false)).thenReturn(false);

        mockMvc.perform(post("/api/permissions/requests/r1/deny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> assertThat(
                        mvcResult.getResponse().getContentAsString()).contains("\"ok\":true"));
    }
}
