package com.example.vatica.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器（迭代 13 I13-2）：enabled=true 时校验 Authorization: Bearer。
 * 公开路径 = 登录/注册 / OpenAPI / 根索引；OPTIONS 预检直接放行。
 * 迭代 14.5：`/api/auth/me` 必须走 JWT 鉴权，因此公开路径从 `/api/auth/*`
 * 收紧为登录/注册两个精确端点，并把 token 到期时间写入请求属性供 /me 回显。
 */
public class JwtAuthInterceptor implements HandlerInterceptor {

    /** /api/auth/me 读取的到期时间请求属性。 */
    public static final String EXPIRES_AT_ATTRIBUTE = "vatica.auth.expiresAt";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login", "/api/auth/register");
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/v3/api-docs", "/swagger-ui", "/");

    private final AuthProperties props;
    private final JwtService jwt;
    private final ObjectMapper mapper;

    public JwtAuthInterceptor(AuthProperties props, JwtService jwt, ObjectMapper mapper) {
        this.props = props;
        this.jwt = jwt;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!props.enabled() || HttpMethod.OPTIONS.matches(request.getMethod()) || isPublic(request.getRequestURI())) {
            if (!props.enabled() && !HttpMethod.OPTIONS.matches(request.getMethod()) && !isPublic(request.getRequestURI())) {
                // 过渡期：鉴权关闭时所有请求使用本地默认身份，自配槽位等 user 维度功能可用
                RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "local"));
            }
            return true;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        try {
            JwtService.Claims claims = jwt.verify(token);
            RequestIdentity identity = new RequestIdentity(claims.userId(), claims.orgId(),
                    claims.role(), claims.username());
            request.setAttribute(EXPIRES_AT_ATTRIBUTE, claims.expiresAt());
            if (request.getRequestURI().startsWith("/mcp") && !"PLATFORM_ADMIN".equals(identity.role())) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(mapper.writeValueAsString(
                        Map.of("message", "操作失败：MCP Server 当前仅允许平台管理员访问。")));
                return false;
            }
            RequestIdentityContext.set(identity);
            return true;
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(mapper.writeValueAsString(Map.of("message", e.getMessage())));
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        RequestIdentityContext.clear();
    }

    private static boolean isPublic(String uri) {
        return PUBLIC_PATHS.contains(uri)
                || PUBLIC_PREFIXES.stream().anyMatch(prefix -> prefix.equals("/") ? uri.equals("/") : uri.startsWith(prefix));
    }
}
