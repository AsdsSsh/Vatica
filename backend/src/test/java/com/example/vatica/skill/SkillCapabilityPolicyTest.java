package com.example.vatica.skill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 迭代 20D：权限能力词表与工具最低权限必须由代码机械校验。 */
class SkillCapabilityPolicyTest {

    @Test
    void acceptsKnownCapabilityThatCoversTool() {
        SkillManifest manifest = manifest(List.of("search_knowledge_base"), List.of("knowledge:read"));

        assertThatCode(() -> SkillCapabilityPolicy.validate(manifest)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOrUnknownCapabilities() {
        assertThatThrownBy(() -> SkillCapabilityPolicy.validate(
                manifest(List.of("write_file"), List.of("workspace:read"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("workspace:write");
        assertThatThrownBy(() -> SkillCapabilityPolicy.validate(
                manifest(List.of("search_knowledge_base"), List.of("network:open"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未知权限能力");
    }

    @Test
    void rejectsToolWithoutGovernancePolicy() {
        assertThatThrownBy(() -> SkillCapabilityPolicy.validate(
                manifest(List.of("arbitrary_code"), List.of("compute:execute"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少能力策略");
    }

    private static SkillManifest manifest(List<String> tools, List<String> permissions) {
        return new SkillManifest("test-skill", "1.0.0", "测试 Skill", "能力声明测试", "research",
                tools, permissions, "只执行测试步骤", "2026-08-19T00:00:00Z");
    }
}
