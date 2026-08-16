package com.example.vatica.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.workspace.WorkspaceStore;

/** 迭代 14：权限配置以服务端数据库为事实来源。 */
@Service
public class PermissionPolicyService {
    private final UserPermissionProfileRepository profiles;
    private final WorkspaceRootRecordRepository roots;
    private final PermissionRuleRecordRepository rules;
    private final WorkspaceStore workspaceStore;

    public PermissionPolicyService(UserPermissionProfileRepository profiles, WorkspaceRootRecordRepository roots,
            PermissionRuleRecordRepository rules, WorkspaceStore workspaceStore) {
        this.profiles = profiles;
        this.roots = roots;
        this.rules = rules;
        this.workspaceStore = workspaceStore;
    }

    public FilePermissionPolicy current() {
        RequestIdentity identity = RequestIdentityContext.require();
        Path tenantRoot = workspaceStore.root(identity);
        FilePermissionMode mode = profiles.findById(identity.userId())
                .map(UserPermissionProfile::getMode).orElse(FilePermissionMode.WORKSPACE_WRITE);
        List<WorkspaceRootRecord> stored = roots.findByUserId(identity.userId());
        boolean read = stored.isEmpty() || stored.get(0).isReadable();
        boolean write = stored.isEmpty() || stored.get(0).isWritable();
        if (mode == FilePermissionMode.READ_ONLY) {
            write = false;
        }
        return new FilePermissionPolicy(mode, List.of(new WorkspaceRoot(tenantRoot.toString(), read, write)));
    }

    @Transactional
    public FilePermissionPolicy save(FilePermissionPolicy requested) {
        RequestIdentity identity = RequestIdentityContext.require();
        FilePermissionPolicy normalized = requested == null
                ? FilePermissionPolicy.defaultPolicy(workspaceStore.root(identity)) : requested.normalized();
        UserPermissionProfile profile = profiles.findById(identity.userId())
                .orElseGet(() -> new UserPermissionProfile(identity.userId(), identity.orgId(), normalized.mode()));
        profile.setMode(normalized.mode());
        profiles.save(profile);
        roots.deleteByUserId(identity.userId());
        boolean read = normalized.workspaceRoots().isEmpty() || normalized.workspaceRoots().get(0).read();
        boolean write = normalized.workspaceRoots().isEmpty() || normalized.workspaceRoots().get(0).write();
        Path root = workspaceStore.root(identity);
        roots.save(new WorkspaceRootRecord(identity.userId(), identity.orgId(), root.toString(), read, write));
        return current();
    }

    @Transactional
    public void remember(Path path, FileAccess access) {
        RequestIdentity identity = RequestIdentityContext.require();
        String normalized = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        Path root = workspaceStore.root(identity);
        if (!Path.of(normalized).startsWith(Path.of(root.toString().toLowerCase(Locale.ROOT)))) {
            throw new IllegalArgumentException("操作失败：不能记住用户工作区之外的授权。");
        }
        if (!rules.existsByUserIdAndPathAndAccess(identity.userId(), normalized, access)) {
            rules.save(new PermissionRuleRecord(identity.userId(), identity.orgId(), normalized, access));
        }
    }

    public boolean isRemembered(Path path, FileAccess access) {
        RequestIdentity identity = RequestIdentityContext.require();
        String target = path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        return rules.findByUserId(identity.userId()).stream()
                .filter(rule -> rule.getAccess() == access)
                .map(PermissionRuleRecord::getPath)
                .anyMatch(rule -> target.equals(rule) || target.startsWith(rule + java.io.File.separator));
    }
}
