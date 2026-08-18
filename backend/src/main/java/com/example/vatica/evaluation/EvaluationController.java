package com.example.vatica.evaluation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 18B：固定评测任务目录，只读且要求登录。 */
@RestController
@RequestMapping("/api/evaluation")
public class EvaluationController {

    private final BenchmarkCatalog catalog;

    public EvaluationController(BenchmarkCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/benchmark-cases")
    public List<BenchmarkCase> benchmarkCases() {
        RequestIdentityContext.require();
        return catalog.cases();
    }
}
