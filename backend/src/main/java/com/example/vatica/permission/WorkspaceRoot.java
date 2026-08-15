package com.example.vatica.permission;

/**
 * 一个工作区根（迭代 11）：目录级授权，读写分开。
 * 对应 Codex writable_roots / Claude additionalDirectories。
 */
public record WorkspaceRoot(String path, boolean read, boolean write) {

    public WorkspaceRoot {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("操作失败：工作区根路径不能为空。");
        }
        path = path.trim();
    }
}
