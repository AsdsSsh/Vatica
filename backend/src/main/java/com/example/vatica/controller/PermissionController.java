package com.example.vatica.controller;

import java.util.Map;

import com.example.vatica.permission.FilePermissionRequestService;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件权限决定接口（迭代 11；迭代 14 服务端化）：接收当前用户的审批决定，
 * remember=true 时先持久化服务端规则，再解除对应工具调用的阻塞等待。
 */
@RestController
@RequestMapping("/api/permissions/requests")
public class PermissionController {

    private final FilePermissionRequestService requestService;

    public PermissionController(FilePermissionRequestService requestService) {
        this.requestService = requestService;
    }

    /** @param remember true 表示将该用户的路径授权持久化到服务端。 */
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
