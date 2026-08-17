package com.example.vatica.auth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口（迭代 13 I13-2）：注册 / 登录，返回 JWT 与用户信息。
 * 迭代 14.5：新增 {@code GET /api/auth/me}，服务端身份是账号态唯一事实来源；
 * 鉴权关闭时返回 LOCAL 本地学习模式，不伪装成云账号登录态。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;
    private final AuthProperties props;

    public AuthController(AuthService service, AuthProperties props) {
        this.service = service;
        this.props = props;
    }

    public record RegisterRequest(String username, String password, String orgName) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record AuthResponse(String token, Long userId, String username, Long orgId, String role) {
    }

    /**
     * 当前用户契约（迭代 14.5 I14.5-1）：
     * userId/orgId 在本地学习模式下为 null，role=LOCAL；云端登录态下由 JWT 拦截器写入。
     */
    public record CurrentUserResponse(Long userId, String username, Long orgId, String role, String expiresAt) {
    }

    @PostMapping("/register")
    public AuthResponse register(@RequestBody RegisterRequest request) {
        AuthService.AuthResult result = service.register(request.username(), request.password(), request.orgName());
        return toResponse(result);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest request) {
        AuthService.AuthResult result = service.login(request.username(), request.password());
        return toResponse(result);
    }

    /** 鉴权关闭：明确展示本地学习模式；鉴权开启：/me 已在拦截器完成 JWT 校验，直接回显身份。 */
    @GetMapping("/me")
    public CurrentUserResponse me(HttpServletRequest request) {
        if (!props.enabled()) {
            return new CurrentUserResponse(null, "本地学习模式", null, "LOCAL", null);
        }
        RequestIdentity identity = RequestIdentityContext.require();
        Object expiresAt = request.getAttribute(JwtAuthInterceptor.EXPIRES_AT_ATTRIBUTE);
        return new CurrentUserResponse(identity.userId(), identity.username(), identity.orgId(),
                identity.role(), expiresAt == null ? null : expiresAt.toString());
    }

    private static AuthResponse toResponse(AuthService.AuthResult result) {
        AppUser user = result.user();
        return new AuthResponse(result.token(), user.getId(), user.getUsername(), user.getOrgId(), user.getRole());
    }
}
