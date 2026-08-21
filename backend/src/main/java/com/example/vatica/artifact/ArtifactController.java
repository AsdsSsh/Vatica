package com.example.vatica.artifact;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 25C：任务详情之外的统一产物查询入口。 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService service;

    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    @GetMapping
    public List<ArtifactView> list(@RequestParam String subjectType, @RequestParam String subjectId) {
        return service.list(subjectType, subjectId);
    }
}
