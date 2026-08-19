package com.example.vatica.skill;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 20A：Skill 目录与组织级启停、版本切换、回滚 API。 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillCatalogService service;

    public SkillController(SkillCatalogService service) {
        this.service = service;
    }

    public record VersionRequest(String version) {
    }

    @GetMapping
    public List<SkillCatalogService.SkillView> catalog() {
        return service.catalog();
    }

    @PostMapping("/{skillId}/enable")
    public SkillCatalogService.SkillView enable(@PathVariable String skillId) {
        return service.enable(skillId);
    }

    @PostMapping("/{skillId}/disable")
    public SkillCatalogService.SkillView disable(@PathVariable String skillId) {
        return service.disable(skillId);
    }

    @PutMapping("/{skillId}/active-version")
    public SkillCatalogService.SkillView activate(@PathVariable String skillId,
            @RequestBody VersionRequest request) {
        return service.activate(skillId, request == null ? null : request.version());
    }

    @PostMapping("/{skillId}/rollback")
    public SkillCatalogService.SkillView rollback(@PathVariable String skillId) {
        return service.rollback(skillId);
    }
}
