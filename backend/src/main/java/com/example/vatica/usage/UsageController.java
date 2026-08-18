package com.example.vatica.usage;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 15 I15-13：用量查询接口（用户维度；敏感内容永不落 usage 表）。 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService service;

    public UsageController(UsageService service) {
        this.service = service;
    }

    @GetMapping("/today")
    public UsageService.TodayView today() {
        return service.today(RequestIdentityContext.require());
    }

    @GetMapping("/requests/{requestId}")
    public List<UsageService.RequestCallView> requestCalls(@PathVariable String requestId) {
        return service.requestCalls(RequestIdentityContext.require(), requestId);
    }

    /** 迭代 18：Legacy / AgentScope 任务质量与耗时基线。 */
    @GetMapping("/reliability")
    public UsageService.ReliabilityView reliability() {
        return service.reliability(RequestIdentityContext.require());
    }
}
