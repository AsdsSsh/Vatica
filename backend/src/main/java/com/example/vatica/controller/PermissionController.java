package com.example.vatica.controller;

import java.util.Map;

import com.example.vatica.permission.FilePermissionRequestService;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件权限决定接口（迭代 11）：权限规则/永久授权都由前端 localStorage 持有，
 * 后端只接收一次决定并解除对应工具调用的阻塞等待。
 */
@RestController
@RequestMapping("/api/permissions/requests")
public class PermissionController {

    private final FilePermissionRequestService requestService;

    public PermissionController(FilePermissionRequestService requestService) {
        this.requestService = requestService;
    }

    /** @param remember true 表示前端会记住授权；后端仅记录日志，不持久化。 */
    public record DecisionRequest(boolean remember) {
    }

    @PostMapping("/{requestId}/approve")
    public Map<String, Object> approve(@PathVariable String requestId,
            @RequestBody DecisionRequest body) {
        requestService.decide(requestId, true, body == null ? false : body.remember());
        return Map.of("ok", true);
    }

    @PostMapping("/{requestId}/deny")
    public Map<String, Object> deny(@PathVariable String requestId) {
        requestService.decide(requestId, false, false);
        return Map.of("ok", true);
    }
}
