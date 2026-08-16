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

// ═══ 鉴权（迭代 13 I13-7）：JWT 存 localStorage，请求统一带 Authorization ═══

const AUTH_TOKEN_KEY = "vatica.authToken";

export function getAuthToken(): string | null {
  try {
    return localStorage.getItem(AUTH_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setAuthToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(AUTH_TOKEN_KEY, token);
    else localStorage.removeItem(AUTH_TOKEN_KEY);
  } catch {
    // 隐私模式忽略
  }
}

export interface AuthResponse {
  token: string;
  userId: number;
  username: string;
  orgId: number;
  role: string;
}

export async function registerUser(username: string, password: string, orgName?: string): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password, orgName }),
  });
  if (!res.ok) throw await toRequestError(res, "注册失败");
  return res.json();
}

export async function loginUser(username: string, password: string): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw await toRequestError(res, "登录失败");
  return res.json();
}

function authHeaders(): Record<string, string> {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

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
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res;
}

/** PUT 辅助（迭代 13 I13-4）：与 post 同一套错误契约 + Authorization 头。 */
async function putJson(path: string, body: unknown): Promise<Response> {
  const res = await fetch(`${getApiBase()}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res;
}

/** DELETE 辅助（迭代 13 I13-4）：无需请求体，但同样携带 Authorization 头。 */
async function deleteJson(path: string): Promise<Response> {
  const res = await fetch(`${getApiBase()}${path}`, {
    method: "DELETE",
    headers: authHeaders(),
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res;
}

async function getJson(path: string): Promise<Response> {
  const res = await fetch(`${getApiBase()}${path}`, { headers: authHeaders() });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res;
}

// ═══ 会话 ═══

/**
 * 文件权限快照（迭代 11，前端 permissions.ts 为事实来源）。
 */
export interface WorkspaceRoot {
  path: string;
  read: boolean;
  write: boolean;
}

export interface FilePermissionPolicy {
  mode: "READ_ONLY" | "WORKSPACE_WRITE" | "DANGER_FULL_ACCESS";
  workspaceRoots: WorkspaceRoot[];
}

/** 后端经 SSE 推送的一次文件权限请求。 */
export interface FilePermissionRequest {
  requestId: string;
  channel: string;
  path: string;
  access: "READ" | "WRITE";
  mode: string;
  description: string;
  createdAt: string;
}

/** 后端经 SSE 推送的一次工具调用活动（迭代 12 I12-4）。 */
export interface ToolActivity {
  tool: string;
  phase: "start" | "end" | "failed";
  durationMs?: number;
  error?: string;
}

export type ChatStreamEvent =
  | { kind: "text"; content: string }
  | { kind: "permission"; request: FilePermissionRequest }
  | { kind: "tool"; activity: ToolActivity };

/**
 * SSE 流式对话（迭代 6 I6-5；迭代 11 支持权限请求事件）：
 * 文本事件逐块产出打字机内容；`event: permission_request` 产出权限请求，由调用方弹窗决定。
 */
export async function* streamChat(
  message: string,
  sessionId: string,
  permission: FilePermissionPolicy,
  model?: string,
  signal?: AbortSignal,
): AsyncGenerator<ChatStreamEvent> {
  const res = await post("/api/chat/stream", { message, sessionId, model, permission }, signal);
  if (!res.body) {
    throw new Error(`请求失败（HTTP ${res.status}）`);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "message";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, idx).trimEnd();
        buffer = buffer.slice(idx + 1);
        if (line.startsWith("event:")) {
          eventName = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          const payload = line.slice(5).replace(/^ /, "");
          if (eventName === "permission_request") {
            try {
              yield { kind: "permission", request: JSON.parse(payload) as FilePermissionRequest };
            } catch {
              // 忽略坏帧
            }
          } else if (eventName === "tool_activity") {
            try {
              yield { kind: "tool", activity: JSON.parse(payload) as ToolActivity };
            } catch {
              // 忽略坏帧
            }
          } else {
            yield { kind: "text", content: payload };
          }
          eventName = "message";
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/** 批准文件权限请求；remember=true 表示前端已写入 localStorage。 */
export async function approvePermissionRequest(requestId: string, remember: boolean): Promise<void> {
  await post(`/api/permissions/requests/${requestId}/approve`, { remember });
}

/** 拒绝文件权限请求。 */
export async function denyPermissionRequest(requestId: string): Promise<void> {
  await post(`/api/permissions/requests/${requestId}/deny`, {});
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

/** 模型槽位（迭代 13 I13-3 掩码契约：apiKey 只用于提交，响应为 null）。 */
export interface ModelSlot {
  id: string;
  name: string;
  /** 协议：openai = OpenAI 兼容端点；anthropic = Anthropic Messages 协议。 */
  protocol: "openai" | "anthropic";
  baseUrl: string;
  /**
   * 提交语义：非空 = 设置新 key；空串 = 清除；null = 保持现有 key。
   * 后端响应永远不回传完整 key。
   */
  apiKey: string | null;
  model: string;
  temperature: number;
  enabled: boolean;
  apiKeySet: boolean;
  apiKeyHint: string | null;
}

export async function fetchModelSlots(): Promise<ModelSlot[]> {
  return (await getJson("/api/models")).json();
}

export async function saveModelSlots(slots: ModelSlot[]): Promise<ModelSlot[]> {
  const res = await fetch(`${getApiBase()}/api/models`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(slots),
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res.json();
}

// ═══ 我的模型（迭代 13 I13-4：用户自配槽位 /api/models/user-slots）═══

/** 用户自配模型槽位（后端 UserModelService.View；完整 apiKey 永不返回）。 */
export interface UserModelSlotView {
  id: string;
  ownerId: number;
  name: string;
  /** 协议：openai = OpenAI 兼容端点；anthropic = Anthropic Messages 协议。 */
  protocol: "openai" | "anthropic";
  baseUrl: string;
  model: string;
  temperature: number;
  enabled: boolean;
  /**
   * EPHEMERAL = key 仅本机/请求级，云端不落库；
   * ENCRYPTED_AT_REST = key 信封加密保存在云端。
   */
  credentialMode: "EPHEMERAL" | "ENCRYPTED_AT_REST";
  apiKeySet: boolean;
  apiKeyHint: string | null;
}

/**
 * 用户槽位提交体（后端 UserModelService.SaveRequest）：
 * apiKey 为 null 时保持现有 key；EPHEMERAL 模式下后端忽略 apiKey。
 */
export interface UserModelSlotSaveRequest {
  name: string;
  protocol: "openai" | "anthropic";
  baseUrl: string;
  model: string;
  temperature: number;
  enabled: boolean;
  credentialMode: "EPHEMERAL" | "ENCRYPTED_AT_REST";
  apiKey: string | null;
}

export async function fetchUserModelSlots(): Promise<UserModelSlotView[]> {
  return (await getJson("/api/models/user-slots")).json();
}

export async function createUserModelSlot(
  request: UserModelSlotSaveRequest,
): Promise<UserModelSlotView> {
  return (await post("/api/models/user-slots", request)).json();
}

export async function updateUserModelSlot(
  id: string,
  request: UserModelSlotSaveRequest,
): Promise<UserModelSlotView> {
  return (await putJson(`/api/models/user-slots/${id}`, request)).json();
}

/**
 * 切换凭据模式：切到 ENCRYPTED_AT_REST 时必须提供 apiKey
 * （已有云端 key 时传 null 复用）；切回 EPHEMERAL 会立即删除云端密文。
 */
export async function setUserModelCredentialMode(
  id: string,
  credentialMode: "EPHEMERAL" | "ENCRYPTED_AT_REST",
  apiKey: string | null,
): Promise<UserModelSlotView> {
  return (await putJson(`/api/models/user-slots/${id}/credential-mode`, { credentialMode, apiKey })).json();
}

export async function deleteUserModelSlot(id: string): Promise<void> {
  await deleteJson(`/api/models/user-slots/${id}`);
}

// ═══ 外部服务设置（迭代 13 I13-9：AMAP / 邮件 / 数据库，密钥掩码）═══

export interface IntegrationSettingsView {
  amapKeySet: boolean;
  amapKeyHint: string | null;
  imapHost: string;
  imapPort: number;
  smtpHost: string;
  smtpPort: number;
  mailUsername: string;
  mailPasswordSet: boolean;
  mailPasswordHint: string | null;
  dbMode: "H2" | "MYSQL";
  dbHost: string;
  dbPort: number;
  dbDatabase: string;
  dbUsername: string;
  dbPasswordSet: boolean;
  dbPasswordHint: string | null;
}

export async function fetchIntegrationSettings(): Promise<IntegrationSettingsView> {
  return (await getJson("/api/settings/integrations")).json();
}

export async function saveIntegrationSettings(
  body: unknown,
): Promise<IntegrationSettingsView> {
  return (await putJson("/api/settings/integrations", body)).json();
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
  /** 迭代 13：服务重启中断后是否可"继续执行"。 */
  recoverable: boolean;
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

export async function createTask(goal: string, permission?: FilePermissionPolicy): Promise<TaskDetail> {
  return (await post("/api/task", { goal, permission })).json();
}

export async function fetchTaskDetail(id: string): Promise<TaskDetail> {
  return (await getJson(`/api/task/${id}`)).json();
}

/** 任务动作：approve（审批计划/步骤）/ rework（人工返工）/ cancel（终止）/ resume（继续执行，迭代 13）。 */
export async function taskAction(id: string, action: "approve" | "rework" | "cancel" | "resume"): Promise<TaskDetail> {
  return (await post(`/api/task/${id}/${action}`, {})).json();
}

/**
 * 订阅任务进度事件（迭代 7 I7-1）：EventSource + `task` 事件；
 * 订阅即收到后端回放的当前快照。迭代 11 增加 `permission_request` 事件。
 * 返回取消订阅函数。
 */
export function subscribeTaskEvents(
  id: string,
  onEvent: (e: TaskEvent) => void,
  onPermission?: (e: FilePermissionRequest) => void,
): () => void {
  const source = new EventSource(`${getApiBase()}/api/task/${id}/events`);
  source.addEventListener("task", (ev: MessageEvent<string>) => {
    try {
      onEvent(JSON.parse(ev.data));
    } catch {
      // 忽略坏帧（连接抖动时 EventSource 会自动重连）
    }
  });
  source.addEventListener("permission_request", (ev: MessageEvent<string>) => {
    try {
      onPermission?.(JSON.parse(ev.data) as FilePermissionRequest);
    } catch {
      // 忽略坏帧
    }
  });
  return () => source.close();
}
