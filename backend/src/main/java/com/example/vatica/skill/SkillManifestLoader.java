package com.example.vatica.skill;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 只从应用 classpath 加载受版本控制的内置 Manifest，不扫描用户目录或执行任意代码。 */
@Component
public class SkillManifestLoader {

    private static final String PATTERN = "classpath*:vatica-skills/*.json";

    private final ObjectMapper mapper;

    public SkillManifestLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<LoadedManifest> load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
            List<LoadedManifest> result = new ArrayList<>();
            Set<String> releases = new HashSet<>();
            for (Resource resource : resources) {
                byte[] bytes;
                try (InputStream input = resource.getInputStream()) {
                    bytes = input.readAllBytes();
                }
                SkillManifest manifest = mapper.readValue(bytes, SkillManifest.class);
                String release = manifest.id() + "@" + manifest.version();
                if (!releases.add(release)) {
                    throw new IllegalStateException("操作失败：内置 Skill 发布版本重复（" + release + "）。");
                }
                result.add(new LoadedManifest(manifest, sha256(mapper.writeValueAsBytes(manifest)),
                        resource.getFilename()));
            }
            result.sort(Comparator.comparing((LoadedManifest value) -> value.manifest().id())
                    .thenComparing(value -> SemanticVersion.parse(value.manifest().version())));
            return List.copyOf(result);
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：读取内置 Skill manifest 失败。" + e.getMessage(), e);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：无法计算 Skill manifest 指纹。", e);
        }
    }

    public record LoadedManifest(SkillManifest manifest, String checksum, String source) {
    }
}
