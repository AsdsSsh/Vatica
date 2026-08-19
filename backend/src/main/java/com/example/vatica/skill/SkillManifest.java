package com.example.vatica.skill;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 迭代 20A：受控内置 Skill 的不可变发布清单。 */
public record SkillManifest(String id, String version, String displayName, String description,
        String agentRole, List<String> tools, List<String> permissions, String entryPrompt,
        String releasedAt) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_.:-]{1,127}");

    public SkillManifest {
        id = normalize(id);
        version = trim(version);
        displayName = trim(displayName);
        description = trim(description);
        agentRole = normalize(agentRole);
        entryPrompt = trim(entryPrompt);
        releasedAt = trim(releasedAt);
        tools = normalizedNames(tools, "工具");
        permissions = normalizedNames(permissions, "权限");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Skill id 必须是 3-64 位小写字母、数字或连字符。");
        }
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Skill version 必须使用 x.y.z 语义版本。");
        }
        if (displayName.isBlank() || displayName.length() > 80) {
            throw new IllegalArgumentException("Skill displayName 长度必须为 1-80。");
        }
        if (description.isBlank() || description.length() > 500) {
            throw new IllegalArgumentException("Skill description 长度必须为 1-500。");
        }
        if (agentRole.isBlank() || tools.isEmpty() || permissions.isEmpty()) {
            throw new IllegalArgumentException("Skill 必须声明 agentRole、tools 和 permissions。");
        }
        if (entryPrompt.isBlank() || entryPrompt.length() > 2000) {
            throw new IllegalArgumentException("Skill entryPrompt 长度必须为 1-2000。");
        }
        try {
            Instant.parse(releasedAt);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Skill releasedAt 必须是 ISO-8601 时间。", e);
        }
    }

    private static List<String> normalizedNames(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!NAME.matcher(normalized).matches()) {
                throw new IllegalArgumentException("Skill " + field + "名称不合法：" + value);
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
