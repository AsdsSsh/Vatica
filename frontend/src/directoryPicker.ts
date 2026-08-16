/**
 * 目录选择器（迭代 12 I12-8）：Tauri 桌面环境走 dialog 插件拿绝对路径；
 * 浏览器环境无等价绝对路径 API，返回 null，界面保留文本输入降级。
 */
export function isTauriEnv(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

export async function pickDirectory(): Promise<string | null> {
  if (!isTauriEnv()) return null;
  try {
    const { open } = await import("@tauri-apps/plugin-dialog");
    const selected = await open({
      directory: true,
      multiple: false,
      title: "选择任务工作目录",
    });
    return typeof selected === "string" ? selected : null;
  } catch {
    // 插件不可用时降级为文本输入
    return null;
  }
}
