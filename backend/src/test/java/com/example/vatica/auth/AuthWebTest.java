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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 迭代 13 I13-2：JWT 拦截器 401/放行 + 注册登录接口契约。
 * 迭代 14.5：/api/auth/me 200/401/本地模式用例——/me 收紧为受保护路径，
 * 公开路径只剩 register/login，鉴权关闭时 /me 返回 LOCAL 本地学习模式。
 */
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

        @PostMapping("/mcp")
        String mcp() { return "mcp"; }
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

        AppUser user = user(1L, 1L, AppUser.ROLE_ORG_ADMIN, "alice");
        String token = jwt.issue(user);

        String body = mvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("pong");
    }

    @Test
    void registerAndLoginEndpointsReturnTokenShape() throws Exception {
        AuthService service = mock(AuthService.class);
        AppUser user = user(1L, 1L, AppUser.ROLE_PLATFORM_ADMIN, "alice");
        when(service.register("alice", "secret123", "团队A"))
                .thenReturn(new AuthService.AuthResult(user, "tok1"));
        when(service.login("alice", "secret123"))
                .thenReturn(new AuthService.AuthResult(user, "tok2"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(service, new AuthProperties(true, Duration.ofHours(1)))).build();

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

    /** 迭代 14.5：登录注册保持公开，/me 必须携带有效 JWT。 */
    @Test
    void meRequiresJwtWhileLoginAndRegisterStayPublic() throws Exception {
        AuthProperties props = new AuthProperties(true, Duration.ofHours(1));
        AuthService service = mock(AuthService.class);
        AppUser user = user(1L, 1L, AppUser.ROLE_ORG_ADMIN, "alice");
        when(service.login("alice", "secret123")).thenReturn(new AuthService.AuthResult(user, "tok"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(service, props))
                .addInterceptors(new JwtAuthInterceptor(props, jwt, new ObjectMapper()))
                .build();

        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer bad.token.value"))
                .andExpect(status().isUnauthorized());
    }

    /** 迭代 14.5：有效 token 回显身份，expiresAt 来自服务端验签结果。 */
    @Test
    void meReturnsCurrentUserFromVerifiedToken() throws Exception {
        AuthProperties props = new AuthProperties(true, Duration.ofHours(1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(mock(AuthService.class), props))
                .addInterceptors(new JwtAuthInterceptor(props, jwt, new ObjectMapper()))
                .build();

        AppUser user = user(7L, 9L, AppUser.ROLE_ORG_ADMIN, "alice");
        String body = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt.issue(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.path("userId").asLong()).isEqualTo(7L);
        assertThat(json.path("orgId").asLong()).isEqualTo(9L);
        assertThat(json.path("username").asText()).isEqualTo("alice");
        assertThat(json.path("role").asText()).isEqualTo(AppUser.ROLE_ORG_ADMIN);
        assertThat(json.path("expiresAt").asText()).isNotBlank();
    }

    /** 迭代 14.5：鉴权关闭时 /me 明确返回 LOCAL 本地学习模式，不伪装成云账号登录态。 */
    @Test
    void meReturnsLocalLearningModeWhenAuthDisabled() throws Exception {
        AuthProperties props = new AuthProperties(false, Duration.ofHours(1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(mock(AuthService.class), props))
                .addInterceptors(new JwtAuthInterceptor(props, jwt, new ObjectMapper()))
                .build();

        String body = mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.path("userId").isNull()).isTrue();
        assertThat(json.path("orgId").isNull()).isTrue();
        assertThat(json.path("role").asText()).isEqualTo("LOCAL");
        assertThat(json.path("username").asText()).contains("本地学习模式");
        assertThat(json.path("expiresAt").isNull()).isTrue();
    }

    @Test
    void mcpRequiresJwtAndPlatformAdmin() throws Exception {
        AuthProperties props = new AuthProperties(true, Duration.ofHours(1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PingController())
                .addInterceptors(new JwtAuthInterceptor(props, jwt, new ObjectMapper()))
                .build();
        mvc.perform(post("/mcp")).andExpect(status().isUnauthorized());

        AppUser member = user(2L, 1L, AppUser.ROLE_MEMBER, "member");
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + jwt.issue(member)))
                .andExpect(status().isForbidden());

        AppUser admin = user(1L, 1L, AppUser.ROLE_PLATFORM_ADMIN, "admin");
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + jwt.issue(admin)))
                .andExpect(status().isOk());
    }

    private static AppUser user(Long id, Long orgId, String role, String username) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getOrgId()).thenReturn(orgId);
        when(user.getRole()).thenReturn(role);
        when(user.getUsername()).thenReturn(username);
        return user;
    }
}
