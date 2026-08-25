package com.example.vatica.context;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 29D：上下文健康只读 API；默认查看会话，也支持任务门禁状态。 */
@RestController
@RequestMapping("/api/context/health")
public class ContextHealthController {

    private final ContextHealthService service;

    public ContextHealthController(ContextHealthService service) {
        this.service = service;
    }

    @GetMapping
    public ContextHealthView get(
            @RequestParam(defaultValue = "CHAT_SESSION") ContextFactScopeType scopeType,
            @RequestParam String scopeId) {
        return service.get(scopeType, scopeId);
    }
}
