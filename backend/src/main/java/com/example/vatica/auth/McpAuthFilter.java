package com.example.vatica.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/** 迭代 22C：独立 MCP Servlet 不经过 DispatcherServlet，复用同一 JWT/平台管理员门禁。 */
public final class McpAuthFilter extends OncePerRequestFilter {

    private final JwtAuthInterceptor interceptor;

    public McpAuthFilter(JwtAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (interceptor.preHandle(request, response, this)) {
                chain.doFilter(request, response);
            }
        } catch (IOException | ServletException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        } finally {
            interceptor.afterCompletion(request, response, this, null);
        }
    }
}
