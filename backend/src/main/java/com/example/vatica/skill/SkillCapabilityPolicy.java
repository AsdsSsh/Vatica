package com.example.vatica.skill;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 迭代 20D：受控内置 Skill 的能力词表。
 * Manifest permission 必须是已知能力，并覆盖每个工具的最低权限；标签本身不授予权限。
 */
public final class SkillCapabilityPolicy {

    private static final Map<String, Set<String>> TOOL_REQUIREMENTS = Map.ofEntries(
            Map.entry("read_file", Set.of("workspace:read")),
            Map.entry("list_files", Set.of("workspace:read")),
            Map.entry("list_workspace_roots", Set.of("workspace:read")),
            Map.entry("write_file", Set.of("workspace:write")),
            Map.entry("create_word_report", Set.of("workspace:write")),
            Map.entry("create_excel_stats", Set.of("workspace:write")),
            Map.entry("calendar_query", Set.of("pim:read")),
            Map.entry("todo_list", Set.of("pim:read")),
            Map.entry("calendar_create", Set.of("pim:write")),
            Map.entry("calendar_import", Set.of("pim:write")),
            Map.entry("todo_add", Set.of("pim:write")),
            Map.entry("todo_complete", Set.of("pim:write")),
            Map.entry("todo_remind", Set.of("pim:write")),
            Map.entry("mail_query", Set.of("mail:read")),
            Map.entry("mail_send", Set.of("mail:send")),
            Map.entry("search_knowledge_base", Set.of("knowledge:read")),
            Map.entry("calculator", Set.of("compute:execute")),
            Map.entry("text_stats", Set.of("compute:execute")));

    private static final Set<String> KNOWN_PERMISSIONS = Set.of(
            "workspace:read", "workspace:write", "pim:read", "pim:write",
            "mail:read", "mail:send", "knowledge:read", "citation:read",
            "compute:execute", "maps:read");

    private SkillCapabilityPolicy() {
    }

    public static void validate(SkillManifest manifest) {
        validate(manifest.id() + "@" + manifest.version(), manifest.tools(), manifest.permissions());
    }

    public static void validate(String release, List<String> tools, List<String> permissions) {
        Set<String> declared = Set.copyOf(permissions == null ? List.of() : permissions);
        for (String permission : declared) {
            if (!KNOWN_PERMISSIONS.contains(permission)) {
                throw new IllegalStateException("操作失败：Skill " + release
                        + " 声明了未知权限能力（" + permission + "）。");
            }
        }
        for (String tool : tools == null ? List.<String>of() : tools) {
            Set<String> required = requiredPermissions(tool);
            if (required.isEmpty()) {
                throw new IllegalStateException("操作失败：Skill " + release
                        + " 的工具缺少能力策略（" + tool + "）。");
            }
            if (!declared.containsAll(required)) {
                Set<String> missing = new java.util.LinkedHashSet<>(required);
                missing.removeAll(declared);
                throw new IllegalStateException("操作失败：Skill " + release + " 的工具 " + tool
                        + " 缺少权限声明（" + String.join(", ", missing) + "）。");
            }
        }
    }

    private static Set<String> requiredPermissions(String tool) {
        Set<String> exact = TOOL_REQUIREMENTS.get(tool);
        if (exact != null) {
            return exact;
        }
        return tool != null && (tool.startsWith("maps_") || tool.startsWith("amap_"))
                ? Set.of("maps:read") : Set.of();
    }
}
