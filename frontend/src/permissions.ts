/**
 * 前端文件权限中心（迭代 11）：localStorage 是权限事实来源。
 * 每次聊天/任务请求随带本模块生成的权限快照；后端只做机械校验与 ask 等待，
 * 不持久化用户授权。
 */

export type FilePermissionMode = "READ_ONLY" | "WORKSPACE_WRITE" | "DANGER_FULL_ACCESS";

export interface WorkspaceRoot {
  path: string;
  read: boolean;
  write: boolean;
}

export interface FilePermissionPolicy {
  mode: FilePermissionMode;
  workspaceRoots: WorkspaceRoot[];
}

const STORAGE_KEY = "vatica.filePermissions";

export const DEFAULT_PERMISSION_POLICY: FilePermissionPolicy = {
  mode: "WORKSPACE_WRITE",
  workspaceRoots: [],
};

export function loadPermissionPolicy(): FilePermissionPolicy {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return structuredClone(DEFAULT_PERMISSION_POLICY);
    const parsed = JSON.parse(raw) as Partial<FilePermissionPolicy>;
    return {
      mode: parsed.mode ?? DEFAULT_PERMISSION_POLICY.mode,
      workspaceRoots: Array.isArray(parsed.workspaceRoots)
        ? parsed.workspaceRoots.filter((r) => r && typeof r.path === "string" && r.path.trim())
        : [],
    };
  } catch {
    return structuredClone(DEFAULT_PERMISSION_POLICY);
  }
}

export function savePermissionPolicy(policy: FilePermissionPolicy): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(policy));
  } catch {
    // 隐私模式等 localStorage 不可用时忽略
  }
}

/** 永久记住一次授权：按路径合并进工作区根。 */
export function rememberWorkspaceRoot(path: string, access: "READ" | "WRITE"): FilePermissionPolicy {
  const policy = loadPermissionPolicy();
  const clean = path.trim();
  const existing = policy.workspaceRoots.find((r) => r.path.trim() === clean);
  if (existing) {
    if (access === "READ") existing.read = true;
    if (access === "WRITE") existing.write = true;
  } else {
    policy.workspaceRoots.push({ path: clean, read: access === "READ", write: access === "WRITE" });
  }
  savePermissionPolicy(policy);
  return policy;
}

export function removeWorkspaceRoot(path: string): FilePermissionPolicy {
  const policy = loadPermissionPolicy();
  policy.workspaceRoots = policy.workspaceRoots.filter((r) => r.path.trim() !== path.trim());
  savePermissionPolicy(policy);
  return policy;
}

export function clearPermissionPolicy(): FilePermissionPolicy {
  const policy = structuredClone(DEFAULT_PERMISSION_POLICY);
  savePermissionPolicy(policy);
  return policy;
}
