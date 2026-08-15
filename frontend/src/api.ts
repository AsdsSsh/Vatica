/**
 * 后端 API 客户端（迭代 6 起；迭代 9 I9-3/I9-4 前后端分离）：
 * - 类型与 OpenAPI 契约（GET /v3/api-docs）逐字段对齐，后端为唯一事实来源；
 * - API 基地址可配置：设置界面保存的运行时覆盖值 > VITE_API_BASE 构建期变量 >
 *   默认 http://localhost:8080（桌面版后端随壳以 sidecar 启动，8080 为默认形态）；
 *   改基址即切换后端部署（P1-6 云端后端零代码切换）。
 * - 错误契约：后端非 2xx 统一返回 {message}，这里解析并透出服务端消息。
 */

// ═══ API 基地址（迭代 9 I9-4 可配置）═══

const DEFAULT_API_BASE = "http://localhost:8080";
const API_BASE_STORAGE_KEY = "vatica.apiBase";

function normalizeBase(base: string): string {
  return base.trim().replace(/\/+$/, "");
}

/** 当前生效的 API 基地址（运行时覆盖 > 构建期变量 > 默认）。 */
export function getApiBase(): string {
  try {
    const override = localStorage.getItem(API_BASE_STORAGE_KEY);
    if (override && override.trim()) return normalizeBase(override);
  } catch {
    // localStorage 不可用（隐私模式等）时忽略
  }
  const env = (import.meta.env?.VITE_API_BASE as string | undefined) ?? "";
  if (env.trim()) return normalizeBase(env);
  return DEFAULT_API_BASE;
}

/** 保存运行时覆盖基址（"服务设置"用；传空串=恢复默认）。 */
export function setApiBase(base: string): void {
  try {
    if (!base.trim()) {
      localStorage.removeItem(API_BASE_STORAGE_KEY);
    } else {
      localStorage.setItem(API_BASE_STORAGE_KEY, normalizeBase(base));
    }
  } catch {
    // 忽略（隐私模式）
  }
}

// ═══ 请求与错误契约（迭代 9 I9-3）═══

/** 后端统一错误响应 {message}；解析失败（网关/网络层非 JSON 体）时回退兜底文案。 */
async function toRequestError(res: Response, fallback: string): Promise<Error> {
  let message = "";
  try {
    const body = (await res.json()) as { message?: string };
    if (body && typeof body.message === "string") message = body.message;
  } catch {
    // 保持兜底文案
  }
  return new Error(message || fallback);
}

async function post(path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
  const res = await fetch(`${getApiBase()}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res;
}

async function getJson(path: string): Promise<Response> {
  const res = await fetch(`${getApiBase()}${path}`);
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res;
}

// ═══ 会话 ═══

/** 生成会话 ID（会话短期记忆的 sessionId，由前端持有）。 */
export function newSessionId(): string {
  return crypto.randomUUID();
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

// ═══ 模型（与 OpenAPI schema 对齐：/api/chat/models、/api/models）═══

/** 模型清单项（后端 ModelInfoDto）。 */
export interface ModelInfo {
  id: string;
  name: string;
  configured: boolean;
}

export async function fetchModels(): Promise<ModelInfo[]> {
  return (await getJson("/api/chat/models")).json();
}

/** 模型槽位（后端 ModelSlot，迭代 8.5 模型配置中心：GET/PUT /api/models）。 */
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
  return (await getJson("/api/models")).json();
}

export async function saveModelSlots(slots: ModelSlot[]): Promise<ModelSlot[]> {
  const res = await fetch(`${getApiBase()}/api/models`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(slots),
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res.json();
}

/** 连通性测试结果（POST /api/models/test，失败时 error 为根因消息）。 */
export interface ModelTestResult {
  ok: boolean;
  reply?: string;
  error?: string;
}

export async function testModelConnection(slot: ModelSlot): Promise<ModelTestResult> {
  return (await post("/api/models/test", slot)).json();
}

// ═══ 任务（与 OpenAPI schema 对齐：/api/task，详情与 SSE 事件同构）═══

/** 任务计划步骤（后端 TaskPlan.TaskStep 的投影）。 */
export interface TaskStep {
  id: number;
  description: string;
  needsApproval: boolean;
  approved: boolean;
  result: string | null;
  /** 依赖的步骤 id（迭代 6 波次并行；无=依赖上一步）。 */
  dependsOn?: number[];
}

/**
 * 任务详情（后端 TaskDetailDto）：详情接口与 SSE 事件负载同构
 * （事件 = 详情 + type 字段），一个类型两处复用。
 */
export interface TaskDetail {
  id: string;
  goal: string;
  status: string;
  createdAt: string;
  /** 下一个待执行步骤下标（全部完成/未开始执行时为 -1）。 */
  currentStep: number;
  /** 挂起审批的步骤 id（无挂起时为 -1）。 */
  pendingStepId: number;
  /** Judge 评分（未评测为 null）。 */
  score: number | null;
  /** PASS / FAIL（未评测为 null）。 */
  verdict: string | null;
  reworkCount: number;
  /** 失败/终止/评测不合格原因（正常为 null）。 */
  error: string | null;
  /** 任务计划（后端解析后的 JSON 对象；数据损坏时为提示字符串）。 */
  plan?: { steps?: TaskStep[] } | string;
}

/** SSE 进度事件负载 = 完整任务快照 + 事件类型。 */
export type TaskEvent = TaskDetail & { type: string };

/** 任务概要（后端 TaskSummaryDto，最近任务列表用）。 */
export interface TaskSummary {
  id: string;
  goal: string;
  status: string;
  createdAt: string;
}

export async function fetchRecentTasks(): Promise<TaskSummary[]> {
  return (await getJson("/api/task")).json();
}

export async function createTask(goal: string): Promise<TaskDetail> {
  return (await post("/api/task", { goal })).json();
}

export async function fetchTaskDetail(id: string): Promise<TaskDetail> {
  return (await getJson(`/api/task/${id}`)).json();
}

/** 任务动作：approve（审批计划/步骤）/ rework（人工返工）/ cancel（终止）。 */
export async function taskAction(id: string, action: "approve" | "rework" | "cancel"): Promise<TaskDetail> {
  return (await post(`/api/task/${id}/${action}`, {})).json();
}

/**
 * 订阅任务进度事件（迭代 7 I7-1）：EventSource + `task` 事件；
 * 订阅即收到后端回放的当前快照。返回取消订阅函数。
 */
export function subscribeTaskEvents(id: string, onEvent: (e: TaskEvent) => void): () => void {
  const source = new EventSource(`${getApiBase()}/api/task/${id}/events`);
  source.addEventListener("task", (ev: MessageEvent<string>) => {
    try {
      onEvent(JSON.parse(ev.data));
    } catch {
      // 忽略坏帧（连接抖动时 EventSource 会自动重连）
    }
  });
  return () => source.close();
}

// ═══ 文件产物（与 OpenAPI schema 对齐：/api/files，后端 FileArtifactDto）═══

/** 文件产物（迭代 7 I7-3）。 */
export interface Artifact {
  name: string;
  size: number;
  modifiedAt: string;
  absolutePath: string;
}

export async function fetchFiles(): Promise<Artifact[]> {
  return (await getJson("/api/files")).json();
}
