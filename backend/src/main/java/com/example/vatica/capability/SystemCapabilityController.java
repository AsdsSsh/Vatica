package com.example.vatica.capability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 23C：桌面端启动后读取的执行前提摘要。 */
@RestController
@RequestMapping("/api/system/capabilities")
public class SystemCapabilityController {

    private final SystemCapabilityService service;

    public SystemCapabilityController(SystemCapabilityService service) {
        this.service = service;
    }

    @GetMapping
    public SystemCapabilityService.Snapshot snapshot() {
        return service.snapshot(RequestIdentityContext.require());
    }
}
