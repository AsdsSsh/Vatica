package com.example.vatica.evaluation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 18B/18C：固定评测目录及当前用户的只读评测报告。 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final BenchmarkCatalog catalog;
    private final EvaluationService service;

    public EvaluationController(BenchmarkCatalog catalog, EvaluationService service) {
        this.catalog = catalog;
        this.service = service;
    }

    @GetMapping("/benchmark-cases")
    public List<BenchmarkCase> benchmarkCases() {
        RequestIdentityContext.require();
        return catalog.cases();
    }

    /** 迭代 18C：当前用户的固定评测对照报告与发布门禁。 */
    @GetMapping("/report")
    public EvaluationService.EvaluationReport report() {
        return service.report(RequestIdentityContext.require());
    }
}
