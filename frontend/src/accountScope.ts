/**
 * 账号级 localStorage 分桶（迭代 14.5）：会话、用户模型本机 Key、邮箱本机密码等
 * 本地缓存统一按 JWT 的 org/user 分桶，A 账号登出后切换到 B 账号时不会读到 A 的缓存。
 * 这里只用于缓存命名，身份事实源仍是服务端 GET /api/auth/me。
 */

export function accountStorageScope(): string {
  try {
    const token = localStorage.getItem("vatica.authToken");
    if (!token) return "local";
    const parts = token.split(".");
    if (parts.length !== 3) throw new Error("invalid token");
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const payload = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="))) as {
      sub?: number | string;
      org?: number | string;
    };
    if (payload.sub == null || payload.org == null) throw new Error("missing subject");
    return `org-${payload.org}.user-${payload.sub}`;
  } catch {
    const token = localStorage.getItem("vatica.authToken");
    if (!token) return "local";
    // 畸形/旧格式 token 也独立分桶，避免回退到本地缓存造成账号间内容泄漏。
    const parts = token.split(".");
    return "token-" + (parts[parts.length - 1]?.slice(0, 16) ?? "unknown");
  }
}
