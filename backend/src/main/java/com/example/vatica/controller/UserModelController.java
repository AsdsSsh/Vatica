package com.example.vatica.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.UserModelService;

/**
 * 用户自配模型槽位接口（迭代 13 I13-4）：按当前身份隔离；
 * 完整 key 永不回传，开关语义见 UserModelService。
 */
@RestController
@RequestMapping("/api/models/user-slots")
public class UserModelController {

    private final UserModelService service;

    public UserModelController(UserModelService service) {
        this.service = service;
    }

    public record ModeRequest(String credentialMode, String apiKey) {
    }

    @GetMapping
    public List<UserModelService.View> list() {
        return service.list(ownerId());
    }

    @PostMapping
    public UserModelService.View create(@RequestBody UserModelService.SaveRequest request) {
        return service.create(ownerId(), request);
    }

    @PutMapping("/{id}")
    public UserModelService.View update(@PathVariable String id,
            @RequestBody UserModelService.SaveRequest request) {
        return service.update(ownerId(), id, request);
    }

    @PutMapping("/{id}/credential-mode")
    public UserModelService.View setMode(@PathVariable String id, @RequestBody ModeRequest request) {
        return service.setMode(ownerId(), id, request.credentialMode(), request.apiKey());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        service.delete(ownerId(), id);
        return Map.of("ok", true);
    }

    private static Long ownerId() {
        RequestIdentity identity = RequestIdentityContext.current();
        return identity == null ? 1L : identity.userId();
    }
}
