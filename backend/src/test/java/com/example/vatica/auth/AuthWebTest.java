package com.example.vatica.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import com.example.vatica.config.AppStateProperties;
import com.example.vatica.secret.FileMasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 13 I13-2：JWT 拦截器 401/放行 + 注册登录接口契约。 */
class AuthWebTest {

    @TempDir
    Path dir;

    private JwtService jwt;

    @RestController
    static class PingController {
        @GetMapping("/api/ping")
        String ping() {
            return "pong";
        }
    }

    @BeforeEach
    void setUp() {
        jwt = new JwtService(new FileMasterKeyProvider(new AppStateProperties(dir.toString())),
                new ObjectMapper(), Duration.ofHours(1));
    }

    @Test
    void protectedApiRejectsMissingAndAcceptsValidToken() throws Exception {
        AuthProperties props = new AuthProperties(true, Duration.ofHours(1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PingController())
                .addInterceptors(new JwtAuthInterceptor(props, jwt, new ObjectMapper()))
                .build();

        mvc.perform(get("/api/ping"))
                .andExpect(status().isUnauthorized());

        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(1L);
        when(user.getOrgId()).thenReturn(1L);
        when(user.getRole()).thenReturn(AppUser.ROLE_ORG_ADMIN);
        when(user.getUsername()).thenReturn("alice");
        String token = jwt.issue(user);

        String body = mvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("pong");
    }

    @Test
    void registerAndLoginEndpointsReturnTokenShape() throws Exception {
        AuthService service = mock(AuthService.class);
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(1L);
        when(user.getUsername()).thenReturn("alice");
        when(user.getOrgId()).thenReturn(1L);
        when(user.getRole()).thenReturn(AppUser.ROLE_PLATFORM_ADMIN);
        when(service.register("alice", "secret123", "团队A"))
                .thenReturn(new AuthService.AuthResult(user, "tok1"));
        when(service.login("alice", "secret123"))
                .thenReturn(new AuthService.AuthResult(user, "tok2"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(service)).build();

        String register = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"orgName\":\"团队A\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(register).contains("\"token\":\"tok1\"").contains("\"role\":\"PLATFORM_ADMIN\"");

        String login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(login).contains("\"token\":\"tok2\"");
    }
}
