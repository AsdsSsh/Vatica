package com.example.vatica.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.AppUser;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.controller.ForbiddenException;
import com.example.vatica.runtime.AgentRegistry;

/**
 * 迭代 20A：内置 Skill 目录与组织级生命周期。
 * Manifest 只描述受控能力，运行时接管留到 20B；身份、权限和审批仍由 Vatica 持有。
 */
@Service
public class SkillCatalogService {

    private final SkillManifestLoader loader;
    private final SkillVersionRepository versions;
    private final SkillInstallationRepository installations;
    private final AgentRegistry agentRegistry;
    private final ToolCallbackProvider localTools;

    public SkillCatalogService(SkillManifestLoader loader, SkillVersionRepository versions,
            SkillInstallationRepository installations, AgentRegistry agentRegistry,
            @Qualifier("vaticaTools") ToolCallbackProvider localTools) {
        this.loader = loader;
        this.versions = versions;
        this.installations = installations;
        this.agentRegistry = agentRegistry;
        this.localTools = localTools;
    }

    public record SkillVersionView(String version, boolean active, boolean latest, List<String> tools,
            List<String> permissions, String releasedAt, String checksum) {
    }

    public record SkillView(String id, String displayName, String description, String agentRole,
            String activeVersion, String latestVersion, String previousVersion, boolean enabled,
            boolean manageable, boolean canRollback, long revision, String updatedAt,
            List<String> tools, List<String> permissions, List<SkillVersionView> versions) {
    }

    /** 20B 交给运行时的不可变快照；不包含身份、审批或业务状态。 */
    public record ExecutionProfile(String id, String version, String displayName, String agentRole,
            List<String> tools, List<String> permissions, String entryPrompt) {
        public ExecutionProfile {
            tools = List.copyOf(tools);
            permissions = List.copyOf(permissions);
        }
    }

    @Transactional
    public List<SkillView> catalog() {
        RequestIdentity identity = RequestIdentityContext.require();
        Catalog catalog = synchronize(identity);
        return catalog.installations().stream().map(installation -> view(installation, catalog.releases()))
                .toList();
    }

    @Transactional
    public SkillView enable(String skillId) {
        return changeEnabled(skillId, true);
    }

    @Transactional
    public SkillView disable(String skillId) {
        return changeEnabled(skillId, false);
    }

    @Transactional
    public SkillView activate(String skillId, String version) {
        RequestIdentity identity = requireManager();
        Catalog catalog = synchronize(identity);
        String normalizedId = normalizeId(skillId);
        String normalizedVersion = version == null ? "" : version.trim();
        List<SkillVersionRecord> releases = releases(catalog.releases(), normalizedId);
        releases.stream().filter(value -> value.getVersion().equals(normalizedVersion)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "操作失败：Skill " + normalizedId + " 不存在版本 " + normalizedVersion + "。"));
        SkillInstallationRecord installation = installation(catalog.installations(), normalizedId);
        installation.activate(normalizedVersion, identity.userId());
        return view(installation, catalog.releases());
    }

    @Transactional
    public SkillView rollback(String skillId) {
        RequestIdentity identity = requireManager();
        Catalog catalog = synchronize(identity);
        SkillInstallationRecord installation = installation(catalog.installations(), normalizeId(skillId));
        if (installation.getPreviousVersion() == null
                || versions.findBySkillIdAndVersion(installation.getSkillId(), installation.getPreviousVersion()).isEmpty()) {
            throw new IllegalArgumentException("操作失败：该 Skill 没有可回滚版本。");
        }
        installation.rollback(identity.userId());
        return view(installation, catalog.releases());
    }

    private SkillView changeEnabled(String skillId, boolean enabled) {
        RequestIdentity identity = requireManager();
        Catalog catalog = synchronize(identity);
        SkillInstallationRecord installation = installation(catalog.installations(), normalizeId(skillId));
        if (enabled) {
            installation.enable(identity.userId());
        } else {
            installation.disable(identity.userId());
        }
        return view(installation, catalog.releases());
    }

    /**
     * 20B：为任务步骤解析组织级 Skill 执行快照。
     * 新步骤按角色选择当前激活版本；已有步骤传入固定 id/version，升级后仍复用原版本。
     */
    @Transactional
    public Optional<ExecutionProfile> resolveForExecution(RequestIdentity identity, String requestedRole,
            String pinnedSkillId, String pinnedVersion) {
        requireIdentity(identity);
        Catalog catalog = synchronize(identity);
        return resolve(catalog, requestedRole, pinnedSkillId, pinnedVersion);
    }

    /** 新计划批量绑定，整份计划只同步一次 manifest/安装状态。 */
    @Transactional
    public Map<String, ExecutionProfile> resolveForPlanning(RequestIdentity identity, Set<String> requestedRoles) {
        requireIdentity(identity);
        Catalog catalog = synchronize(identity);
        Map<String, ExecutionProfile> result = new LinkedHashMap<>();
        if (requestedRoles == null) {
            return Map.of();
        }
        for (String requestedRole : requestedRoles) {
            String role = agentRegistry.normalizeId(requestedRole);
            resolve(catalog, role, null, null).ifPresent(profile -> result.put(role, profile));
        }
        return Map.copyOf(result);
    }

    private Optional<ExecutionProfile> resolve(Catalog catalog, String requestedRole,
            String pinnedSkillId, String pinnedVersion) {
        String role = agentRegistry.normalizeId(requestedRole);
        boolean hasPinnedId = pinnedSkillId != null && !pinnedSkillId.isBlank();
        boolean hasPinnedVersion = pinnedVersion != null && !pinnedVersion.isBlank();
        if (hasPinnedId != hasPinnedVersion) {
            throw new IllegalStateException("操作失败：任务步骤的 Skill 固定版本信息不完整。");
        }
        if (hasPinnedId) {
            String skillId = normalizeId(pinnedSkillId);
            SkillInstallationRecord installation = installation(catalog.installations(), skillId);
            requireEnabled(installation);
            SkillVersionRecord release = release(catalog.releases(), skillId, pinnedVersion.trim());
            requireRole(release, role);
            return Optional.of(profile(release));
        }

        List<ResolvedInstallation> candidates = catalog.installations().stream()
                .map(value -> new ResolvedInstallation(value,
                        release(catalog.releases(), value.getSkillId(), value.getActiveVersion())))
                .filter(value -> value.release().getAgentRole().equals(role))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("操作失败：角色 " + role
                    + " 匹配多个 Skill，当前版本需要在计划中明确 skillId。");
        }
        ResolvedInstallation selected = candidates.getFirst();
        requireEnabled(selected.installation());
        return Optional.of(profile(selected.release()));
    }

    /** 同步 classpath 发布物，并为首次访问的组织安装每个 Skill 的最新版本。 */
    private Catalog synchronize(RequestIdentity identity) {
        requireIdentity(identity);
        Set<String> availableTools = java.util.Arrays.stream(localTools.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()).collect(Collectors.toUnmodifiableSet());
        for (SkillManifestLoader.LoadedManifest loaded : loader.load()) {
            validate(loaded.manifest(), availableTools);
            versions.findBySkillIdAndVersion(loaded.manifest().id(), loaded.manifest().version())
                    .ifPresentOrElse(existing -> checkImmutable(existing, loaded),
                            () -> versions.save(new SkillVersionRecord(loaded)));
        }
        List<SkillVersionRecord> releases = versions.findAllByOrderBySkillIdAsc();
        Map<String, List<SkillVersionRecord>> grouped = group(releases);
        Map<String, SkillInstallationRecord> installed = installations
                .findByOrgIdOrderBySkillIdAsc(identity.orgId()).stream()
                .collect(Collectors.toMap(SkillInstallationRecord::getSkillId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        for (Map.Entry<String, List<SkillVersionRecord>> entry : grouped.entrySet()) {
            if (!installed.containsKey(entry.getKey())) {
                String latest = newest(entry.getValue()).getVersion();
                SkillInstallationRecord created = installations.save(
                        new SkillInstallationRecord(identity.orgId(), entry.getKey(), latest, identity.userId()));
                installed.put(entry.getKey(), created);
            }
        }
        return new Catalog(releases, List.copyOf(installed.values()));
    }

    private void validate(SkillManifest manifest, Set<String> availableTools) {
        if (!agentRegistry.normalizeId(manifest.agentRole()).equals(manifest.agentRole())) {
            throw new IllegalStateException("操作失败：Skill " + manifest.id() + " 声明了未知 Agent 角色 "
                    + manifest.agentRole() + "。");
        }
        for (String tool : manifest.tools()) {
            if (!availableTools.contains(tool)) {
                throw new IllegalStateException("操作失败：Skill " + manifest.id() + " 声明的工具不存在（" + tool + "）。");
            }
            if (!agentRegistry.allowsTool(manifest.agentRole(), tool)) {
                throw new IllegalStateException("操作失败：Skill " + manifest.id() + " 越过角色 "
                        + manifest.agentRole() + " 的工具白名单（" + tool + "）。");
            }
        }
    }

    private static void checkImmutable(SkillVersionRecord existing, SkillManifestLoader.LoadedManifest loaded) {
        if (!existing.getChecksum().equals(loaded.checksum())) {
            throw new IllegalStateException("操作失败：内置 Skill 已发布版本内容被修改（"
                    + loaded.manifest().id() + "@" + loaded.manifest().version() + "），请发布新版本。");
        }
    }

    private SkillView view(SkillInstallationRecord installation, List<SkillVersionRecord> allReleases) {
        List<SkillVersionRecord> releases = releases(allReleases, installation.getSkillId()).stream()
                .sorted(Comparator.comparing((SkillVersionRecord value) -> SemanticVersion.parse(value.getVersion()))
                        .reversed())
                .toList();
        SkillVersionRecord latest = releases.getFirst();
        SkillVersionRecord active = releases.stream()
                .filter(value -> value.getVersion().equals(installation.getActiveVersion())).findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：Skill 激活版本不存在（"
                        + installation.getSkillId() + "@" + installation.getActiveVersion() + "）。"));
        List<SkillVersionView> releaseViews = releases.stream().map(value -> new SkillVersionView(
                value.getVersion(), value.getVersion().equals(active.getVersion()),
                value.getVersion().equals(latest.getVersion()),
                sorted(value.getTools()), sorted(value.getPermissions()), value.getReleasedAt().toString(),
                value.getChecksum())).toList();
        return new SkillView(installation.getSkillId(), active.getDisplayName(), active.getDescription(),
                active.getAgentRole(), active.getVersion(), latest.getVersion(), installation.getPreviousVersion(),
                installation.isEnabled(), manageable(RequestIdentityContext.require()),
                installation.getPreviousVersion() != null, installation.getRevision(),
                installation.getUpdatedAt().toString(), sorted(active.getTools()), sorted(active.getPermissions()),
                releaseViews);
    }

    private static Map<String, List<SkillVersionRecord>> group(List<SkillVersionRecord> releases) {
        return releases.stream().collect(Collectors.groupingBy(SkillVersionRecord::getSkillId,
                LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
    }

    private static List<SkillVersionRecord> releases(List<SkillVersionRecord> allReleases, String skillId) {
        List<SkillVersionRecord> result = allReleases.stream().filter(value -> value.getSkillId().equals(skillId))
                .toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("操作失败：Skill 不存在（" + skillId + "）。");
        }
        return result;
    }

    private static SkillVersionRecord newest(List<SkillVersionRecord> values) {
        return values.stream().max(Comparator.comparing(value -> SemanticVersion.parse(value.getVersion())))
                .orElseThrow();
    }

    private static SkillInstallationRecord installation(List<SkillInstallationRecord> values, String skillId) {
        return values.stream().filter(value -> value.getSkillId().equals(skillId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作失败：Skill 不存在（" + skillId + "）。"));
    }

    private static SkillVersionRecord release(List<SkillVersionRecord> values, String skillId, String version) {
        return values.stream().filter(value -> value.getSkillId().equals(skillId)
                        && value.getVersion().equals(version))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "操作失败：Skill 固定版本不存在（" + skillId + "@" + version + "）。"));
    }

    private static void requireEnabled(SkillInstallationRecord installation) {
        if (!installation.isEnabled()) {
            throw new IllegalArgumentException("操作失败：Skill 已停用（" + installation.getSkillId() + "）。");
        }
    }

    private static void requireRole(SkillVersionRecord release, String role) {
        if (!release.getAgentRole().equals(role)) {
            throw new IllegalStateException("操作失败：Skill " + release.getSkillId() + "@" + release.getVersion()
                    + " 不属于任务角色 " + role + "。");
        }
    }

    private static ExecutionProfile profile(SkillVersionRecord release) {
        return new ExecutionProfile(release.getSkillId(), release.getVersion(), release.getDisplayName(),
                release.getAgentRole(), sorted(release.getTools()), sorted(release.getPermissions()),
                release.getEntryPrompt());
    }

    private static void requireIdentity(RequestIdentity identity) {
        if (identity == null || identity.userId() == null || identity.orgId() == null) {
            throw new IllegalStateException("操作失败：当前执行链路缺少用户身份，请重新登录后重试。");
        }
    }

    private static RequestIdentity requireManager() {
        RequestIdentity identity = RequestIdentityContext.require();
        if (!manageable(identity)) {
            throw new ForbiddenException("操作失败：只有组织管理员可以管理 Skill 生命周期。");
        }
        return identity;
    }

    private static boolean manageable(RequestIdentity identity) {
        String role = identity == null ? "" : identity.role();
        return AppUser.ROLE_PLATFORM_ADMIN.equals(role) || AppUser.ROLE_ORG_ADMIN.equals(role)
                || "LOCAL".equals(role);
    }

    private static String normalizeId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z][a-z0-9-]{2,63}")) {
            throw new IllegalArgumentException("操作失败：Skill id 不合法。");
        }
        return id;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private record Catalog(List<SkillVersionRecord> releases, List<SkillInstallationRecord> installations) {
    }

    private record ResolvedInstallation(SkillInstallationRecord installation, SkillVersionRecord release) {
    }
}
