package com.example.vatica.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 20A：classpath Manifest 发现、版本与声明格式门禁。 */
class SkillManifestLoaderTest {

    @Test
    void loadsBundledImmutableReleases() {
        List<SkillManifestLoader.LoadedManifest> releases = new SkillManifestLoader(new ObjectMapper()).load();

        assertThat(releases).hasSize(5);
        assertThat(releases.stream().map(value -> value.manifest().id()).distinct().toList()).hasSize(4);
        assertThat(releases).extracting(value -> value.manifest().id() + "@" + value.manifest().version())
                .contains("knowledge-research@1.0.0", "knowledge-research@1.1.0");
        assertThat(releases).allSatisfy(value -> assertThat(value.checksum()).hasSize(64));
    }

    @Test
    void rejectsLooseVersionAndInvalidPermissionName() {
        assertThatThrownBy(() -> manifest("1.0", List.of("knowledge:read")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("语义版本");
        assertThatThrownBy(() -> manifest("1.0.0", List.of("Knowledge Read")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("权限名称不合法");
    }

    private static SkillManifest manifest(String version, List<String> permissions) {
        return new SkillManifest("test-skill", version, "测试 Skill", "测试清单格式", "research",
                List.of("search_knowledge_base"), permissions, "测试入口提示", "2026-08-19T00:00:00Z");
    }
}
