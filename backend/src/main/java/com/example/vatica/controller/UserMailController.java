package com.example.vatica.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.Map;

import com.example.vatica.mail.UserMailService;
import com.example.vatica.mail.MailConnectionSettings;
import com.example.vatica.mail.MailCredentialContext;
import com.example.vatica.tool.MailTools;

@RestController
@RequestMapping("/api/mail/settings")
public class UserMailController {
    private final UserMailService service;
    private final MailTools mailTools;
    public UserMailController(UserMailService service, MailTools mailTools) {
        this.service = service;
        this.mailTools = mailTools;
    }
    @GetMapping public UserMailService.View get() { return service.get(); }
    @PutMapping public UserMailService.View save(@RequestBody UserMailService.SaveRequest request) {
        return service.save(request);
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) MailConnectionSettings ephemeral) {
        MailCredentialContext.set(ephemeral);
        try {
            return Map.of("ok", true, "message", mailTools.testConnection());
        } finally {
            MailCredentialContext.clear();
        }
    }
}
