package com.example.vatica.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.AdminGuard;
import com.example.vatica.config.IntegrationSettings;
import com.example.vatica.config.IntegrationSettingsService;

/**
 * 外部服务设置（迭代 13 I13-9）：AMAP / 邮件 / 数据库。
 * 响应只回掩码；数据库与 AMAP 保存后重启生效。
 */
@RestController
@RequestMapping("/api/settings/integrations")
public class IntegrationSettingsController {

    private final IntegrationSettingsService service;

    public IntegrationSettingsController(IntegrationSettingsService service) {
        this.service = service;
    }

    public record SecretView(boolean set, String hint) {
    }

    public record View(boolean amapKeySet, String amapKeyHint, String imapHost, int imapPort,
            String smtpHost, int smtpPort, String mailUsername, boolean mailPasswordSet, String mailPasswordHint,
            String dbMode, String dbHost, int dbPort, String dbDatabase, String dbUsername,
            boolean dbPasswordSet, String dbPasswordHint) {
    }

    /** 迭代 13.5：外部服务密钥属于平台级敏感设置，仅平台管理员可读可改。 */
    @GetMapping
    public View get() {
        AdminGuard.requirePlatformAdmin();
        IntegrationSettings s = service.load();
        return view(s);
    }

    @PutMapping
    public View save(@RequestBody IntegrationSettings request) {
        AdminGuard.requirePlatformAdmin();
        return view(service.save(request));
    }

    private static View view(IntegrationSettings s) {
        Mask amap = mask(s.amap().apiKey());
        Mask mail = mask(s.mail().password());
        Mask db = mask(s.db().password());
        return new View(
                amap.set(), amap.hint(),
                s.mail().imapHost(), s.mail().imapPort(), s.mail().smtpHost(), s.mail().smtpPort(),
                s.mail().username(), mail.set(), mail.hint(),
                s.db().mode(), s.db().host(), s.db().port(), s.db().database(), s.db().username(),
                db.set(), db.hint());
    }

    private static record Mask(boolean set, String hint) {
    }

    private static Mask mask(String value) {
        if (value == null || value.isBlank()) {
            return new Mask(false, null);
        }
        String visible = value.length() <= 4 ? value : value.substring(value.length() - 4);
        return new Mask(true, "…" + visible);
    }
}
