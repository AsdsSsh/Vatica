package com.example.vatica.tool;

import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.FileSandboxPolicy;
import com.example.vatica.permission.WorkspaceRoot;

import io.agentscope.core.tool.Tool;

/**
 * 工作区查询工具（list_workspace_roots，迭代 11）：让 Planner/Executor 知道
 * 当前沙盒模式与可访问的工作区根，规划不靠猜。
 */
public final class WorkspaceTools {

    private final FileSandboxPolicy sandboxPolicy;

    public WorkspaceTools(FileSandboxPolicy sandboxPolicy) {
        this.sandboxPolicy = sandboxPolicy;
    }

    @Tool(name = "list_workspace_roots", description = "查询当前文件沙盒模式与已授权的工作区根目录（含读写权限）。"
            + "规划/执行涉及文件路径前应先调用本工具：工作区内直接操作，工作区外会触发用户权限确认。")
    public String listWorkspaceRoots() {
        FilePermissionPolicy policy = sandboxPolicy.currentPolicy();
        StringBuilder sb = new StringBuilder("沙盒模式=").append(policy.mode()).append('\n');
        sb.append("工作区根：\n");
        for (WorkspaceRoot root : policy.workspaceRoots()) {
            sb.append("- ").append(root.path())
                    .append(" 读=").append(root.read() ? "允许" : "拒绝")
                    .append(" 写=").append(root.write() ? "允许" : "拒绝")
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
