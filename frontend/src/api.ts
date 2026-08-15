/** 后端 API 基地址：开发与生产均指向本地 sidecar（Tauri 打包后后端以子进程随壳启动）。 */
export const API_BASE = "http://localhost:8080";

/** 生成会话 ID（会话短期记忆的 sessionId，由前端持有）。 */
export function newSessionId(): string {
  return crypto.randomUUID();
}

/**
 * SSE 流式对话（迭代 6 I6-5）：fetch + ReadableStream 逐行解析 `data:` 事件，
 * 逐块产出文本增量（打字机渲染）。支持 AbortSignal 中断。
 *
 * 注：后端 SSE 用 POST（要带请求体），浏览器 EventSource 只支持 GET，
 * 所以这里用 fetch 流式读（项目讲解稿 7-01 的既定结论）。
 */
export async function* streamChat(
  message: string,
  sessionId: string,
  signal?: AbortSignal,
): AsyncGenerator<string> {
  const res = await fetch(`${API_BASE}/api/chat/stream`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message, sessionId }),
    signal,
  });
  if (!res.ok || !res.body) {
    throw new Error(`请求失败（HTTP ${res.status}）`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, idx).trimEnd();
        buffer = buffer.slice(idx + 1);
        if (line.startsWith("data:")) {
          yield line.slice(5).replace(/^ /, "");
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/** 任务概要（右侧任务面板列表用）。 */
export interface TaskSummary {
  id: string;
  goal: string;
  status: string;
  createdAt: string;
}

/** 最近任务列表（迭代 6 右侧面板先接列表；步骤级实时进度在迭代 7）。 */
export async function fetchRecentTasks(): Promise<TaskSummary[]> {
  const res = await fetch(`${API_BASE}/api/task`);
  if (!res.ok) {
    throw new Error(`请求失败（HTTP ${res.status}）`);
  }
  return res.json();
}
