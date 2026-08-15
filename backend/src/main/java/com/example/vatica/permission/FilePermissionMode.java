package com.example.vatica.permission;

/** 沙盒模式（迭代 11，对齐 Codex / WorkBuddy）。 */
public enum FilePermissionMode {
    /** 只读：工作区内可读，写入需要确认。 */
    READ_ONLY,
    /** 工作区读写：工作区内自动放行，越界 on-request。 */
    WORKSPACE_WRITE,
    /** 完全访问：跳过工作区边界（仍保留 .vatica 内部状态写保护）。 */
    DANGER_FULL_ACCESS
}
