package com.example.vatica.permission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次聊天/任务请求携带的权限快照（迭代 11）。
 * 前端 localStorage 是权限事实来源；后端只按本快照机械判定。
 */
public record FilePermissionPolicy(FilePermissionMode mode, List<WorkspaceRoot> workspaceRoots) {

    public FilePermissionPolicy {
        if (mode == null) {
            mode = FilePermissionMode.WORKSPACE_WRITE;
        }
        if (workspaceRoots == null) {
            workspaceRoots = List.of();
        }
    }

    /** 后端兜底默认策略：workspace-write，唯一工作区根 = 进程启动目录（Codex 语义）。 */
    public static FilePermissionPolicy defaultPolicy(Path defaultRoot) {
        String root = defaultRoot.toAbsolutePath().normalize().toString();
        return new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE,
                List.of(new WorkspaceRoot(root, true, true)));
    }

    /** 归一化路径并过滤空值。 */
    public FilePermissionPolicy normalized() {
        List<WorkspaceRoot> roots = new ArrayList<>();
        for (WorkspaceRoot r : workspaceRoots) {
            String path = r.path().trim();
            if (path.isEmpty() || roots.stream().anyMatch(x -> samePath(x.path(), path))) {
                continue;
            }
            roots.add(new WorkspaceRoot(path, r.read(), r.write()));
        }
        return new FilePermissionPolicy(mode, List.copyOf(roots));
    }

    private static boolean samePath(String a, String b) {
        return Path.of(a).toAbsolutePath().normalize().toString()
                .equalsIgnoreCase(Path.of(b).toAbsolutePath().normalize().toString());
    }
}
