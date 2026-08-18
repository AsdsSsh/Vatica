package com.example.vatica.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.AgentModelBindingService;

/** 迭代 17C：Agent 模型绑定设置与当前生效槽位元数据。 */
@RestController
@RequestMapping("/api/models/agent-bindings")
public class AgentModelBindingController {

    private final AgentModelBindingService service;

    public AgentModelBindingController(AgentModelBindingService service) {
        this.service = service;
    }

    @GetMapping
    public AgentModelBindingService.SettingsView settings() {
        return service.settings(RequestIdentityContext.require());
    }

    @PutMapping
    public AgentModelBindingService.BindingView save(
            @RequestBody AgentModelBindingService.BindingRequest request) {
        return service.save(RequestIdentityContext.require(), request);
    }
}
