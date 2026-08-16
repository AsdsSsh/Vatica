package com.example.vatica.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号接口（迭代 13 I13-2）：注册 / 登录，返回 JWT 与用户信息。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    public record RegisterRequest(String username, String password, String orgName) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record AuthResponse(String token, Long userId, String username, Long orgId, String role) {
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

    private static AuthResponse toResponse(AuthService.AuthResult result) {
        AppUser user = result.user();
        return new AuthResponse(result.token(), user.getId(), user.getUsername(), user.getOrgId(), user.getRole());
    }
}
