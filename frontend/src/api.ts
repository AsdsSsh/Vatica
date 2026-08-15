/** 后端 API 基地址：开发与生产均指向本地 sidecar（Tauri 打包后后端以子进程随壳启动）。 */
export const API_BASE = "http://localhost:8080";

/** 生成会话 ID（会话短期记忆的 sessionId，由前端持有）。 */
export function newSessionId(): string {
  return crypto.randomUUID();
}

async function post(path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok) {
    throw new Error(`请求失败（HTTP ${res.status}）`);
  }
  return res;
}

/**
 * SSE 流式对话（迭代 6 I6-5）：fetch + ReadableStream 逐行解析 `data:` 事件，
 * 逐块产出文本增量（打字机渲染）。支持 AbortSignal 中断与模型选择（迭代 7）。
 *
 * 注：后端 SSE 用 POST（要带请求体），浏览器 EventSource 只支持 GET，
 * 所以这里用 fetch 流式读（项目讲解稿 7-01 的既定结论）。
 */
export async function* streamChat(
  message: string,
  sessionId: string,
  model?: string,
  signal?: AbortSignal,
): AsyncGenerator<string> {
  const res = await post("/api/chat/stream", { message, sessionId, model }, signal);
  if (!res.body) {
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

/** 任务计划步骤（后端 TaskPlan.TaskStep 的投影）。 */
export interface TaskStep {
  id: number;
  description: string;
  needsApproval: boolean;
  approved: boolean;
  result: string | null;
}

/** 任务详情（含评测字段，迭代 5.5/7）。 */
export interface TaskDetail {
  id: string;
  goal: string;
  status: string;
  createdAt?: string;
  score: number | null;
  verdict: string | null;
  reworkCount: number;
  error?: string | null;
  currentStep?: number;
  pendingStepId?: number;
  plan?: { steps?: TaskStep[] } | string;
}

/** SSE 进度事件负载 = 完整任务快照 + 事件类型。 */
export type TaskEvent = TaskDetail & { type: string };

/** 任务概要（任务列表用）。 */
export interface TaskSummary {
  id: string;
  goal: string;
  status: string;
  createdAt: string;
}

export async function fetchRecentTasks(): Promise<TaskSummary[]> {
  const res = await fetch(`${API_BASE}/api/task`);
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return res.json();
}

export async function createTask(goal: string): Promise<TaskDetail> {
  const res = await post("/api/task", { goal });
  return res.json();
}

export async function fetchTaskDetail(id: string): Promise<TaskDetail> {
  const res = await fetch(`${API_BASE}/api/task/${id}`);
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return res.json();
}

/** 任务动作：approve（审批计划/步骤）/ rework（人工返工）/ cancel（终止）。 */
export async function taskAction(id: string, action: "approve" | "rework" | "cancel"): Promise<TaskDetail> {
  const res = await post(`/api/task/${id}/${action}`, {});
  return res.json();
}

/**
 * 订阅任务进度事件（迭代 7 I7-1）：EventSource + `task` 事件；
 * 订阅即收到后端回放的当前快照。返回取消订阅函数。
 */
export function subscribeTaskEvents(id: string, onEvent: (e: TaskEvent) => void): () => void {
  const source = new EventSource(`${API_BASE}/api/task/${id}/events`);
  source.addEventListener("task", (ev: MessageEvent<string>) => {
    try {
      onEvent(JSON.parse(ev.data));
    } catch {
      // 忽略坏帧（连接抖动时 EventSource 会自动重连）
    }
  });
  return () => source.close();
}

/** 文件产物（迭代 7 I7-3）。 */
export interface Artifact {
  name: string;
  size: number;
  modifiedAt: string;
  absolutePath: string;
}

export async function fetchFiles(): Promise<Artifact[]> {
  const res = await fetch(`${API_BASE}/api/files`);
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return res.json();
}

/** 模型清单（迭代 7 I7-5）。 */
export interface ModelInfo {
  id: string;
  name: string;
  configured: boolean;
}

export async function fetchModels(): Promise<ModelInfo[]> {
  const res = await fetch(`${API_BASE}/api/chat/models`);
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return res.json();
}

/** 模型槽位（迭代 8.5 模型配置中心：GET/PUT /api/models）。 */
export interface ModelSlot {
  id: string;
  name: string;
  /** 协议：openai = OpenAI 兼容端点；anthropic = Anthropic Messages 协议。 */
  protocol: "openai" | "anthropic";
  baseUrl: string;
  apiKey: string;
  model: string;
  temperature: number;
  enabled: boolean;
}

export async function fetchModelSlots(): Promise<ModelSlot[]> {
  const res = await fetch(`${API_BASE}/api/models`);
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return res.json();
}

export async function saveModelSlots(slots: ModelSlot[]): Promise<ModelSlot[]> {
  const res = await fetch(`${API_BASE}/api/models`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(slots),
  });
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return res.json();
}

/** 连通性测试结果（POST /api/models/test，失败时 error 为根因消息）。 */
export interface ModelTestResult {
  ok: boolean;
  reply?: string;
  error?: string;
}

export async function testModelConnection(slot: ModelSlot): Promise<ModelTestResult> {
  const res = await fetch(`${API_BASE}/api/models/test`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(slot),
  });
  if (!res.ok) throw new Error(`请求失败（HTTP ${res.status}）`);
  return res.json();
}
