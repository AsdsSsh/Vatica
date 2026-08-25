/**
 * 后端 API 客户端（迭代 6 起；迭代 9 I9-3/I9-4 前后端分离）：
 * - 类型与 OpenAPI 契约（GET /v3/api-docs）逐字段对齐，后端为唯一事实来源；
 * - API 基地址可配置：设置界面保存的运行时覆盖值 > VITE_API_BASE 构建期变量 >
 *   默认 http://localhost:8080（桌面版后端随壳以 sidecar 启动，8080 为默认形态）；
 *   改基址即切换后端部署（P1-6 云端后端零代码切换）。
 * - 错误契约：后端非 2xx 统一返回 {message}，这里解析并透出服务端消息。
 */

import { accountStorageScope } from "./accountScope";

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
// 迭代 14.5：受保护请求首次 401 统一清理 Token + 广播，避免各组件各写一套逻辑。

const AUTH_TOKEN_KEY = "vatica.authToken";
/** 无效/过期 Token 被统一清理后广播的事件（只对同一 token 广播一次，避免重复弹错）。 */
export const AUTH_EXPIRED_EVENT = "vatica-auth-expired";
/** 需要登录的业务入口请求打开账号面板。 */
export const AUTH_OPEN_EVENT = "vatica-open-auth";
/** 模型槽位保存后广播，供聊天、能力摘要与任务入口刷新可调用状态。 */
export const MODEL_CONFIG_UPDATED_EVENT = "vatica-model-config-updated";

export class AuthExpiredError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AuthExpiredError";
  }
}

/** 统一 401 收口后抛出的错误；组件捕获到它时不再各自弹错（登录态消息由全局监听负责）。 */
export function isAuthExpiredError(e: unknown): boolean {
  return e instanceof Error && e.name === "AuthExpiredError";
}

let handledExpiredToken: string | null = null;

export function getAuthToken(): string | null {
  try {
    return localStorage.getItem(AUTH_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setAuthToken(token: string | null): void {
  try {
    if (token) {
      localStorage.setItem(AUTH_TOKEN_KEY, token);
      handledExpiredToken = null;
    } else {
      localStorage.removeItem(AUTH_TOKEN_KEY);
    }
  } catch {
    // 隐私模式忽略
  }
  window.dispatchEvent(new CustomEvent("vatica-auth-changed"));
}

export interface AuthResponse {
  token: string;
  userId: number;
  username: string;
  orgId: number;
  role: string;
}

/** 当前用户契约（迭代 14.5 I14.5-1，后端 CurrentUserResponse）。 */
export interface CurrentUserView {
  userId: number | null;
  username: string;
  orgId: number | null;
  role: string;
  expiresAt: string | null;
}

/** 服务端身份是账号态唯一事实源；鉴权关闭时返回 role=LOCAL 的本地学习模式。 */
export async function fetchCurrentUser(): Promise<CurrentUserView> {
  return (await getJson("/api/auth/me")).json();
}

export async function registerUser(username: string, password: string, orgName?: string): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password, orgName }),
  });
  if (!res.ok) throw await toRequestError(res, "注册失败", false);
  return res.json();
}

export async function loginUser(username: string, password: string): Promise<AuthResponse> {
  const res = await fetch(`${getApiBase()}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw await toRequestError(res, "登录失败", false);
  return res.json();
}

function authHeaders(): Record<string, string> {
  const token = getAuthToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * 后端统一错误响应 {message}；解析失败（网关/网络层非 JSON 体）时回退兜底文案。
 * 迭代 14.5：handleAuthExpiry=true 时对受保护请求的 401 统一收口——
 * 清 Token、广播 vatica-auth-changed / vatica-auth-expired，且同一 token 只处理一次。
 */
async function toRequestError(res: Response, fallback: string, handleAuthExpiry = true): Promise<Error> {
  let message = "";
  try {
    const body = (await res.json()) as { message?: string };
    if (body && typeof body.message === "string") message = body.message;
  } catch {
    // 保持兜底文案
  }
  const text = message || fallback;
  if (res.status === 401 && handleAuthExpiry) {
    const token = getAuthToken();
    if (token && handledExpiredToken !== token) {
      handledExpiredToken = token;
      setAuthToken(null);
      window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT, { detail: { message: text } }));
    }
    // 没有旧 Token 的匿名请求也归入同一认证错误类型，由业务入口给出登录引导。
    return new AuthExpiredError(text);
  }
  return new Error(text);
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

/** 请求级临时模型凭据（迭代 13 I13-5；迭代 13.5 供 EPHEMERAL 用户模型使用）。 */
export interface EphemeralCredential {
  protocol: "openai" | "anthropic";
  baseUrl: string;
  model: string;
  temperature: number;
  apiKey: string;
}

export interface MailConnectionSettings {
  imapHost: string;
  imapPort: number;
  smtpHost: string;
  smtpPort: number;
  username: string;
  password: string;
}

const EPHEMERAL_MAIL_KEY = "vatica.ephemeralMail";

/** 迭代 14.5：邮箱本机密码按账号分桶，切换账号后不会读到上一账号的密码。 */
function ephemeralMailStorageKey(): string {
  return `${EPHEMERAL_MAIL_KEY}.${accountStorageScope()}`;
}

export function saveEphemeralMailCredential(value: MailConnectionSettings | null): void {
  try {
    if (value) localStorage.setItem(ephemeralMailStorageKey(), JSON.stringify(value));
    else localStorage.removeItem(ephemeralMailStorageKey());
  } catch {
    // 隐私模式忽略
  }
}

export function getEphemeralMailCredential(): MailConnectionSettings | undefined {
  try {
    const raw = localStorage.getItem(ephemeralMailStorageKey());
    return raw ? (JSON.parse(raw) as MailConnectionSettings) : undefined;
  } catch {
    return undefined;
  }
}

// ═══ EPHEMERAL 用户模型的客户端密钥（迭代 13.5）═══
// 后端永不回传完整 key；"仅本机"模式的 key 由前端保存在 localStorage，
// 发消息时以请求级 credential 随请求发出，云端不落库。

const USER_MODEL_KEY_PREFIX = "vatica.userModelKey.";

/** 迭代 14.5：仅本机模型 Key 按账号分桶，A 的 Key 不会被 B 的槽位读到。 */
function userModelStorageKey(slotId: string): string {
  return `${USER_MODEL_KEY_PREFIX}${accountStorageScope()}.${slotId}`;
}

export function saveEphemeralUserModelKey(slotId: string, apiKey: string | null): void {
  try {
    if (apiKey) localStorage.setItem(userModelStorageKey(slotId), apiKey);
    else localStorage.removeItem(userModelStorageKey(slotId));
  } catch {
    // 隐私模式忽略
  }
}

export function getEphemeralUserModelKey(slotId: string): string | null {
  try {
    return localStorage.getItem(userModelStorageKey(slotId));
  } catch {
    return null;
  }
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

/** 后端经 SSE 推送的一次工具调用活动（迭代 12 I12-4；迭代 15 I15-1 补 trace 字段）。 */
export interface ToolActivity {
  tool: string;
  phase: "start" | "end" | "failed";
  durationMs?: number;
  error?: string;
  /** 迭代 15：本次推理链路的 trace id。 */
  traceId?: string;
  /** 迭代 15：脱敏后的工具输入摘要。 */
  inputSummary?: string;
  /** 迭代 15：工具输出头尾摘要（长输出已截断）。 */
  outputSummary?: string;
  /** 迭代 15：原始输出长度（判断是否被截断）。 */
  outputLength?: number;
}

/** 迭代 15 I15-13：SSE 收尾 usage 事件。 */
export interface UsageSummary {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  reasoningTokens: number;
  contextFillRatio: number | null;
}

/** 迭代 16 I16-2：所有 SSE 业务事件的统一信封。 */
export interface SseEventEnvelope<T = unknown> {
  id: string;
  type: string;
  data: T;
  ts: string;
}

class NonRetryableSseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NonRetryableSseError";
  }
}

function parseSseEnvelope(eventName: string, eventId: string, dataLines: string[]): SseEventEnvelope {
  if (dataLines.length === 0) return { id: eventId, type: eventName, data: null, ts: "" };
  const raw = dataLines.join("\n");
  let parsed: unknown = raw;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    // 兼容旧服务端的纯文本 data 帧。
  }
  if (parsed && typeof parsed === "object") {
    const candidate = parsed as { id?: unknown; type?: unknown; data?: unknown; ts?: unknown };
    if (typeof candidate.type === "string" && "data" in candidate) {
      return {
        id: typeof candidate.id === "string" ? candidate.id : eventId,
        type: candidate.type,
        data: candidate.data,
        ts: typeof candidate.ts === "string" ? candidate.ts : "",
      };
    }
  }
  return { id: eventId, type: eventName, data: parsed, ts: "" };
}

async function* parseSseBody(body: ReadableStream<Uint8Array>): AsyncGenerator<SseEventEnvelope> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "message";
  let eventId = "";
  let dataLines: string[] = [];
  const flush = (): SseEventEnvelope | null => {
    if (dataLines.length === 0) {
      eventName = "message";
      eventId = "";
      return null;
    }
    const event = parseSseEnvelope(eventName, eventId, dataLines);
    eventName = "message";
    eventId = "";
    dataLines = [];
    return event;
  };
  const consumeLine = (line: string): SseEventEnvelope | null => {
    if (line.endsWith("\r")) line = line.slice(0, -1);
    if (line === "") return flush();
    if (line.startsWith(":")) return null;
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith("id:")) {
      eventId = line.slice(3).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).replace(/^ /, ""));
    }
    return null;
  };
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let index: number;
      while ((index = buffer.indexOf("\n")) >= 0) {
        const event = consumeLine(buffer.slice(0, index));
        buffer = buffer.slice(index + 1);
        if (event) yield event;
      }
    }
    buffer += decoder.decode();
    if (buffer) {
      const event = consumeLine(buffer);
      if (event) yield event;
    }
    const event = flush();
    if (event) yield event;
  } finally {
    reader.releaseLock();
  }
}

/**
 * 迭代 16 I16-3：统一 fetch-SSE 客户端。
 * `reconnect=true` 时会携带最近事件 id 自动重连，并按 id 去重回放帧。
 */
export async function* fetchSse<T = unknown>(
  path: string,
  init: RequestInit = {},
  signal?: AbortSignal,
  reconnect = false,
): AsyncGenerator<SseEventEnvelope<T>> {
  const effectiveSignal = signal ?? init.signal;
  let lastEventId = "";
  let retryCount = 0;
  const seenIds = new Set<string>();
  while (!effectiveSignal?.aborted) {
    try {
      const headers = new Headers(init.headers);
      headers.set("Accept", "text/event-stream");
      if (lastEventId) headers.set("Last-Event-ID", lastEventId);
      const res = await fetch(`${getApiBase()}${path}`, { ...init, headers, signal: effectiveSignal });
      if (!res.ok) {
        const error = await toRequestError(res, `SSE 请求失败（HTTP ${res.status}）`);
        if (res.status < 500) throw new NonRetryableSseError(error.message);
        throw error;
      }
      if (!res.body) throw new Error(`SSE 响应没有可读流（HTTP ${res.status}）`);
      retryCount = 0;
      for await (const event of parseSseBody(res.body)) {
        if (event.id && seenIds.has(event.id)) continue;
        if (event.id) {
          seenIds.add(event.id);
          if (seenIds.size > 512) seenIds.delete(seenIds.values().next().value ?? "");
          lastEventId = event.id;
        }
        yield event as SseEventEnvelope<T>;
      }
      if (!reconnect) return;
    } catch (error) {
      if (effectiveSignal?.aborted) return;
      if (!reconnect || error instanceof NonRetryableSseError || isAuthExpiredError(error)) throw error;
    }
    retryCount += 1;
    if (retryCount > 8) throw new Error("SSE 连接重试次数过多，请稍后重试");
    const delay = Math.min(250 * 2 ** (retryCount - 1), 4_000);
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(resolve, delay);
      effectiveSignal?.addEventListener("abort", () => {
        window.clearTimeout(timer);
        reject(new DOMException("Aborted", "AbortError"));
      }, { once: true });
    });
  }
}

export type ChatStreamEvent =
  | { kind: "text"; content: string }
  | { kind: "reasoning"; content: string }
  | { kind: "usage"; usage: UsageSummary }
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
  credential?: EphemeralCredential,
  deepThinking = false,
): AsyncGenerator<ChatStreamEvent> {
  const body = {
    message,
    sessionId,
    permission,
    deepThinking,
    mailCredential: getEphemeralMailCredential(),
    ...(credential ? { credential } : { model }),
  };
  for await (const event of fetchSse<unknown>("/api/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(body),
  }, signal)) {
    if (event.type === "permission_request") {
      yield { kind: "permission", request: event.data as FilePermissionRequest };
    } else if (event.type === "usage") {
      yield { kind: "usage", usage: event.data as UsageSummary };
    } else if (event.type === "reasoning") {
      const data = event.data as { content?: unknown };
      if (data && typeof data.content === "string") yield { kind: "reasoning", content: data.content };
    } else if (event.type === "tool_activity") {
      yield { kind: "tool", activity: event.data as ToolActivity };
    } else if (typeof event.data === "string") {
      yield { kind: "text", content: event.data };
    }
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

export async function fetchPermissionPolicy(): Promise<FilePermissionPolicy> {
  return (await getJson("/api/permissions/policy")).json();
}

export async function saveServerPermissionPolicy(policy: FilePermissionPolicy): Promise<FilePermissionPolicy> {
  return (await putJson("/api/permissions/policy", policy)).json();
}

// ═══ 用户会话（迭代 14：服务端元数据 + 消息历史）═══

export interface SessionSummaryView {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface SessionDetailView extends SessionSummaryView {
  messages: { role: "USER" | "ASSISTANT"; content: string; createdAt: string }[];
}

export async function fetchSessions(): Promise<SessionSummaryView[]> {
  return (await getJson("/api/sessions")).json();
}

export async function fetchSessionDetail(id: string): Promise<SessionDetailView> {
  return (await getJson(`/api/sessions/${encodeURIComponent(id)}`)).json();
}

export async function upsertSession(id: string, title: string): Promise<SessionSummaryView> {
  return (await putJson(`/api/sessions/${encodeURIComponent(id)}`, { title })).json();
}

export async function deleteRemoteSession(id: string): Promise<void> {
  await deleteJson(`/api/sessions/${encodeURIComponent(id)}`);
}

// ═══ 受控上下文事实（迭代 29B；接口只返回脱敏视图）═══

export type ContextFactScopeType = "CHAT_SESSION" | "TASK" | "SUBJECT";
export type ContextFactType =
  | "USER_CONFIRMATION" | "APPROVAL" | "TASK_GOAL" | "DATE_TIME" | "ARTIFACT_PATH"
  | "EXTERNAL_OBJECT" | "TOOL_OUTCOME" | "OPEN_QUESTION" | "DELIVERY_CONCLUSION";
export type ContextFactStatus = "ACTIVE" | "SUPERSEDED" | "REVOKED";
export type ContextFactTrustLevel = "USER_CONFIRMED" | "SYSTEM_VERIFIED" | "TOOL_OBSERVED" | "AGENT_DERIVED";
export type ContextFactVerificationState = "CURRENT" | "NEEDS_REFRESH" | "UNVERIFIABLE" | "REVOKED";
export type ContextFactSourceType =
  | "USER_INPUT" | "CHAT_MESSAGE" | "TASK" | "TASK_STEP" | "ACTION_EXECUTION" | "ARTIFACT"
  | "CALENDAR_EVENT" | "TODO" | "KNOWLEDGE_CITATION" | "SYSTEM";

export interface ContextFactView {
  id: string;
  scopeType: ContextFactScopeType;
  scopeId: string;
  subjectType: string | null;
  subjectId: string | null;
  factKey: string;
  revision: number;
  factType: ContextFactType;
  displaySummary: string;
  status: ContextFactStatus;
  trustLevel: ContextFactTrustLevel;
  verificationState: ContextFactVerificationState;
  sourceType: ContextFactSourceType;
  sourceId: string;
  sourceVersion: string | null;
  sourceFingerprint: string | null;
  observedAt: string;
  verifiedAt: string | null;
  validUntil: string | null;
  statusReason: string | null;
  revokedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ContextFactCaptureRequest {
  scopeType: ContextFactScopeType;
  scopeId: string;
  subjectType?: string;
  subjectId?: string;
  factKey: string;
  factType: ContextFactType;
  valueJson: string;
  displaySummary: string;
  trustLevel?: ContextFactTrustLevel;
  verificationState?: ContextFactVerificationState;
  sourceType: ContextFactSourceType;
  sourceId: string;
  sourceVersion?: string;
  sourceFingerprint?: string;
  evidenceRefsJson?: string;
  observedAt?: string;
  verifiedAt?: string;
  validUntil?: string;
}

export async function fetchContextFacts(scopeType: ContextFactScopeType, scopeId: string,
  current = true): Promise<ContextFactView[]> {
  const query = new URLSearchParams({ scopeType, scopeId, current: String(current) });
  return (await getJson(`/api/context/facts?${query.toString()}`)).json();
}

export async function captureContextFact(request: ContextFactCaptureRequest): Promise<ContextFactView> {
  return (await post("/api/context/facts", request)).json();
}

export async function revokeContextFact(id: string, reason?: string): Promise<ContextFactView> {
  return (await post(`/api/context/facts/${encodeURIComponent(id)}/revoke`, { reason })).json();
}

export async function refreshContextFactsBySource(sourceType: ContextFactSourceType, sourceId: string,
  reason?: string): Promise<{ affected: number }> {
  return (await post("/api/context/facts/source/refresh", { sourceType, sourceId, reason })).json();
}

// ═══ 上下文健康（迭代 29D；仅状态/水位/计数，不返回原文或事实值）═══

export type ContextHealthStatus = "HEALTHY" | "PROCESSING" | "DEGRADED" | "NEEDS_REFRESH";

export interface ContextHealthView {
  scopeType: ContextFactScopeType;
  scopeId: string;
  overallStatus: ContextHealthStatus;
  summaryStatus: "PENDING" | "SUCCESS" | "FAILED" | null;
  summaryFailureCode: "NONE" | "EMPTY_RESPONSE" | "TIMEOUT" | "TRANSIENT" | "CONFIGURATION" | "UNKNOWN" | null;
  summaryThroughSeq: number;
  summaryRequestedThroughSeq: number;
  uncoveredMessageCount: number;
  fallbackHeadCount: number;
  fallbackTailCount: number;
  recentMessageCount: number;
  summaryAttemptCount: number;
  summaryLastAttemptAt: string | null;
  summaryLastSuccessAt: string | null;
  summaryNextRetryAt: string | null;
  currentFactCount: number;
  staleFactCount: number;
  contextGatePending: boolean;
  reason: string;
  checkedAt: string;
}

export async function fetchContextHealth(scopeType: ContextFactScopeType, scopeId: string): Promise<ContextHealthView> {
  const query = new URLSearchParams({ scopeType, scopeId });
  return (await getJson(`/api/context/health?${query.toString()}`)).json();
}

// ═══ 模型（与 OpenAPI schema 对齐：/api/chat/models、/api/models）═══

/** 模型清单项（后端 ModelInfoDto）。 */
export interface ModelInfo {
  id: string;
  name: string;
  /** 已启用且具有 API Key，或配置为不需要 Key 的本地端点。 */
  configured: boolean;
}

export async function fetchModels(): Promise<ModelInfo[]> {
  return (await getJson("/api/chat/models")).json();
}

/** 模型槽位能力标签（迭代 15 I15-5，与后端 ModelSlot 常量对齐）。 */
export const MODEL_CAPABILITIES = [
  "chat-fast",
  "chat-reason",
  "planner",
  "judge",
  "summarizer",
] as const;

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
  /** 迭代 15：该槽位可承担的角色（空数组 = 仅作手动选择的对话模型）。 */
  capabilities: string[];
  /** 迭代 15 I15-14：prompt 缓存前缀（OpenAI 兼容端点用；空 = 不启用）。 */
  promptCacheKey: string;
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
  dbMode: "H2" | "POSTGRESQL" | "MYSQL";
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

// ═══ 个人工作台（迭代 14：云文件 / PIM / 我的邮箱）═══

export interface WorkspaceEntry {
  path: string;
  size: number;
  directory: boolean;
  modifiedAt: string;
}

export async function fetchWorkspaceFiles(path = ""): Promise<WorkspaceEntry[]> {
  return (await getJson(`/api/workspace/files?path=${encodeURIComponent(path)}`)).json();
}

export async function uploadWorkspaceFile(file: File, directory = ""): Promise<WorkspaceEntry> {
  const data = new FormData();
  data.append("file", file);
  const res = await fetch(`${getApiBase()}/api/workspace/files?directory=${encodeURIComponent(directory)}`, {
    method: "POST",
    headers: authHeaders(),
    body: data,
  });
  if (!res.ok) throw await toRequestError(res, `上传失败（HTTP ${res.status}）`);
  return res.json();
}

export async function downloadWorkspaceFile(path: string): Promise<Blob> {
  const res = await fetch(`${getApiBase()}/api/workspace/files/content?path=${encodeURIComponent(path)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw await toRequestError(res, `下载失败（HTTP ${res.status}）`);
  return res.blob();
}

export async function deleteWorkspaceFile(path: string): Promise<void> {
  await deleteJson(`/api/workspace/files?path=${encodeURIComponent(path)}`);
}

// ═══ 知识库（迭代 19B/27B/27C：授权索引生命周期、权限检索 + PostgreSQL pgvector）═══

export type KnowledgeVisibility = "PRIVATE" | "ORG_SHARED";
export type KnowledgeDocumentStatus = "INDEXING" | "READY" | "FAILED";

export interface KnowledgeDocumentView {
  id: number;
  sourceName: string;
  sourcePath: string;
  visibility: KnowledgeVisibility;
  contentHash: string;
  version: number;
  status: KnowledgeDocumentStatus;
  chunkCount: number;
  totalChunks: number;
  indexedChunks: number;
  progressPercent: number;
  indexAttempt: number;
  errorMessage: string | null;
  updatedAt: string;
}

/** 迭代 27A：pgvector 扩展、Schema、索引和 Embedding 配置就绪度；不返回连接信息或密钥。 */
export interface KnowledgeReadinessView {
  ready: boolean;
  postgres: boolean;
  extensionInstalled: boolean;
  indexReady: boolean;
  extensionVersion: string | null;
  schemaVersion: string | null;
  embeddingProvider: string | null;
  embeddingModel: string | null;
  vectorDimensions: number;
  configFingerprint: string | null;
  message: string | null;
}

export interface KnowledgeCitation {
  citationId: string;
  documentId: number;
  documentName: string;
  sourcePath: string;
  chunkId: number;
  heading: string | null;
  startOffset: number;
  endOffset: number;
  score: number;
  quote: string;
  sourceLocation: string;
  snippet: string;
  documentVersion: number;
  indexVersion: string;
  accessScope: "CURRENT_USER_OWNER" | "ORGANIZATION_SHARED" | "CURRENT_USER_PRIVATE_AND_ORG_SHARED";
}

export interface KnowledgeSearchResult {
  query: string;
  indexVersion: string;
  accessScope: "CURRENT_USER_PRIVATE_AND_ORG_SHARED";
  citations: KnowledgeCitation[];
}

export async function fetchKnowledgeDocuments(): Promise<KnowledgeDocumentView[]> {
  return (await getJson("/api/knowledge/documents")).json();
}

export async function fetchKnowledgeReadiness(): Promise<KnowledgeReadinessView> {
  return (await getJson("/api/knowledge/readiness")).json();
}

export async function importKnowledgeDocument(
  path: string,
  visibility: KnowledgeVisibility,
): Promise<KnowledgeDocumentView> {
  return (await post("/api/knowledge/documents", { path, visibility })).json();
}

export async function deleteKnowledgeDocument(id: number): Promise<void> {
  await deleteJson(`/api/knowledge/documents/${id}`);
}

export async function retryKnowledgeDocument(id: number): Promise<KnowledgeDocumentView> {
  return (await post(`/api/knowledge/documents/${id}/retry`, {})).json();
}

export async function rebuildKnowledgeDocument(id: number): Promise<KnowledgeDocumentView> {
  return (await post(`/api/knowledge/documents/${id}/rebuild`, {})).json();
}

export async function searchKnowledgeBase(query: string, topK = 5): Promise<KnowledgeSearchResult> {
  return (await post("/api/knowledge/search", { query, topK })).json();
}

export interface TodoView {
  id: string;
  title: string;
  due: string | null;
  done: boolean;
  createdAt: string;
}

export interface CalendarEventView {
  id: number;
  summary: string;
  start: string;
  end: string;
  rrule: string | null;
}

/** 迭代 26A：周报只读事实快照；26B 才会在此基础上生成草案。 */
export interface WeeklyReportSourceView {
  source: "CALENDAR" | "TODO" | "KNOWLEDGE" | string;
  status: "READY" | "EMPTY" | "DEGRADED" | "NOT_SELECTED" | string;
  recordCount: number;
  note: string;
}

export interface WeeklyReportCalendarFact {
  eventId: number;
  title: string;
  start: string;
  end: string;
  recurring: boolean;
}

export interface WeeklyReportTodoFact {
  todoId: string;
  title: string;
  due: string;
  done: boolean;
  createdAt: string;
}

export interface WeeklyReportStatistics {
  meetingCount: number;
  todoCount: number;
  completedTodoCount: number;
  pendingTodoCount: number;
  overdueTodoCount: number;
}

export interface WeeklyReportFactsView {
  reportKey: string;
  reportType: "WEEKLY" | "WORK_WEEK" | string;
  from: string;
  to: string;
  sources: WeeklyReportSourceView[];
  calendar: WeeklyReportCalendarFact[];
  todos: WeeklyReportTodoFact[];
  statistics: WeeklyReportStatistics;
  userNotes: string;
  warnings: string[];
  collectedAt: string;
}

/** 迭代 26B：冻结事实快照后的可编辑周报草案。 */
export interface WeeklyReportDraftView {
  id: string;
  status: "DRAFT" | string;
  title: string;
  focus: string;
  risks: string;
  nextPlan: string;
  wordRequested: boolean;
  excelRequested: boolean;
  facts: WeeklyReportFactsView;
  wordPreview: string | null;
  excelPreview: string | null;
  artifacts: ArtifactView[];
  createdAt: string;
  updatedAt: string;
}

/** 迭代 26C：批准前的周报导出计划；邮件动作只生成本地草稿，不发送。 */
export interface WeeklyReportExportView {
  id: string;
  draftId: string;
  status: "DRAFT" | "APPROVED" | "APPLIED" | "FAILED" | "CANCELLED" | string;
  actionPlan: ActionPlanView;
  mailTo: string | null;
  mailSubject: string | null;
  mailBody: string | null;
  artifacts: ArtifactView[];
  error: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 迭代 24A：用户确认后才能创建会议准备草案的日历候选。 */
export interface MeetingCandidate {
  eventId: number;
  title: string;
  start: string;
  end: string;
}

export type MeetingPreparationStatus = "DRAFT" | "REJECTED" | "APPLIED" | "FAILED" | "CANCELLED";

/** 迭代 25A：所有副作用在执行前都以可审阅动作计划返回。 */
export interface ActionPlanItem {
  id: string;
  type: "WRITE_DOCUMENT" | "CREATE_TODO" | string;
  purpose: string;
  target: string;
  expectedChange: string;
  inputSummary: string;
  requiredPermission: string;
  risk: "LOW" | "MEDIUM" | "HIGH" | string;
  idempotencyKey: string;
  approvalStatus: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED" | string;
  executionStatus: "NOT_STARTED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | string;
  result: string | null;
}

export interface ActionPlanView {
  id: string;
  subjectType: string;
  subjectId: string;
  revision: number;
  status: "PREVIEW" | "APPLIED" | "FAILED" | "REJECTED" | "CANCELLED" | string;
  actions: ActionPlanItem[];
}

export interface ArtifactView {
  id: string;
  subjectType: string;
  subjectId: string;
  type: "DOCUMENT" | "TODO" | "DRAFT" | "FAILURE" | string;
  name: string;
  locator: string | null;
  status: "PREVIEW" | "APPROVED" | "READY" | "FAILED" | "REJECTED" | "CANCELLED" | string;
  summary: string | null;
  sourceActionId: string | null;
  idempotencyKey: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MeetingPreparationView {
  id: string;
  status: MeetingPreparationStatus;
  meeting: MeetingCandidate;
  goal: string | null;
  knowledgeRequested: boolean;
  createdAt: string;
  updatedAt: string;
  draft: MeetingPreparationDraft | null;
  documentPath: string | null;
  todoIds: string[];
  rejectionReason: string | null;
  error: string | null;
  actionPlan: ActionPlanView | null;
  artifacts: ArtifactView[];
}

export interface MeetingPreparationEvidence {
  type: "CALENDAR_EVENT" | "USER_INPUT" | string;
  label: string;
  sourceId: string;
  detail: string;
}

export interface MeetingPreparationCitation {
  citationId: string;
  documentName: string;
  sourcePath: string;
  heading: string | null;
  startOffset: number;
  endOffset: number;
  score: number;
  quote: string;
  sourceLocation: string;
  snippet: string;
  documentVersion: number;
  indexVersion: string;
  accessScope: "CURRENT_USER_OWNER" | "ORGANIZATION_SHARED" | "CURRENT_USER_PRIVATE_AND_ORG_SHARED";
}

export interface MeetingTodoDraft {
  title: string;
  due: string;
}

export interface MeetingPreparationDraft {
  meeting: MeetingCandidate;
  goal: string | null;
  evidence: MeetingPreparationEvidence[];
  knowledgeStatus: "READY" | "DEGRADED" | "NOT_REQUESTED";
  knowledgeMessage: string;
  citations: MeetingPreparationCitation[];
  agendaSuggestions: string[];
  openQuestions: string[];
  todoDrafts: MeetingTodoDraft[];
  documentPreview: string;
}

export async function fetchMeetingCandidates(from: string, to: string, topic?: string): Promise<MeetingCandidate[]> {
  const query = new URLSearchParams({ from, to });
  if (topic?.trim()) query.set("topic", topic.trim());
  return (await getJson(`/api/meeting-preparations/candidates?${query.toString()}`)).json();
}

export async function createMeetingPreparation(body: {
  calendarEventId: number;
  goal?: string;
  includeKnowledge: boolean;
}): Promise<MeetingPreparationView> {
  return (await post("/api/meeting-preparations", body)).json();
}

export async function fetchMeetingPreparation(id: string): Promise<MeetingPreparationView> {
  return (await getJson(`/api/meeting-preparations/${encodeURIComponent(id)}`)).json();
}

export async function refreshMeetingPreparationDraft(id: string, body: {
  goal?: string;
  includeKnowledge: boolean;
}): Promise<MeetingPreparationView> {
  const res = await fetch(`${getApiBase()}/api/meeting-preparations/${encodeURIComponent(id)}/draft`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await toRequestError(res, `更新草案失败（HTTP ${res.status}）`);
  return res.json();
}

export async function approveMeetingPreparation(id: string): Promise<MeetingPreparationView> {
  return (await post(`/api/meeting-preparations/${encodeURIComponent(id)}/approve`, {})).json();
}

export async function retryMeetingPreparation(id: string): Promise<MeetingPreparationView> {
  return (await post(`/api/meeting-preparations/${encodeURIComponent(id)}/retry`, {})).json();
}

export async function cancelMeetingPreparation(id: string): Promise<MeetingPreparationView> {
  return (await post(`/api/meeting-preparations/${encodeURIComponent(id)}/cancel`, {})).json();
}

export async function rejectMeetingPreparation(id: string, reason?: string): Promise<MeetingPreparationView> {
  return (await post(`/api/meeting-preparations/${encodeURIComponent(id)}/reject`, { reason: reason || null })).json();
}

export async function fetchRecentMeetingPreparations(): Promise<MeetingPreparationView[]> {
  return (await getJson("/api/meeting-preparations")).json();
}

export async function fetchArtifacts(subjectType: string, subjectId: string): Promise<ArtifactView[]> {
  const query = new URLSearchParams({ subjectType, subjectId });
  return (await getJson(`/api/artifacts?${query.toString()}`)).json();
}

export async function fetchTodos(): Promise<TodoView[]> {
  return (await getJson("/api/pim/todos")).json();
}
export async function addTodo(title: string, due?: string): Promise<TodoView[]> {
  return (await post("/api/pim/todos", { title, due: due || null })).json();
}
export async function completeTodo(id: string): Promise<TodoView[]> {
  const res = await fetch(`${getApiBase()}/api/pim/todos/${encodeURIComponent(id)}/complete`, {
    method: "PATCH", headers: authHeaders(),
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res.json();
}
export async function deleteTodo(id: string): Promise<void> {
  await deleteJson(`/api/pim/todos/${encodeURIComponent(id)}`);
}

export async function fetchCalendarEvents(): Promise<CalendarEventView[]> {
  return (await getJson("/api/pim/events")).json();
}

export async function collectWeeklyReportFacts(body: {
  from: string;
  to: string;
  reportType: "WEEKLY" | "WORK_WEEK";
  includeCalendar: boolean;
  includeTodos: boolean;
  includeKnowledge: boolean;
  userNotes: string;
}): Promise<WeeklyReportFactsView> {
  return (await post("/api/weekly-reports/facts", body)).json();
}

export async function createWeeklyReportDraft(body: {
  from: string;
  to: string;
  reportType: "WEEKLY" | "WORK_WEEK";
  includeCalendar: boolean;
  includeTodos: boolean;
  includeKnowledge: boolean;
  userNotes: string;
  title: string;
  focus: string;
  risks: string;
  nextPlan: string;
  wordRequested: boolean;
  excelRequested: boolean;
}): Promise<WeeklyReportDraftView> {
  return (await post("/api/weekly-reports/drafts", body)).json();
}

export async function updateWeeklyReportDraft(id: string, body: {
  title: string;
  focus: string;
  risks: string;
  nextPlan: string;
  wordRequested: boolean;
  excelRequested: boolean;
}): Promise<WeeklyReportDraftView> {
  const res = await fetch(`${getApiBase()}/api/weekly-reports/drafts/${encodeURIComponent(id)}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await toRequestError(res, `更新周报草案失败（HTTP ${res.status}）`);
  return res.json();
}

export async function prepareWeeklyReportExport(draftId: string, body: {
  wordRequested: boolean;
  excelRequested: boolean;
  mailRequested: boolean;
  mailTo: string;
  mailSubject: string;
}): Promise<WeeklyReportExportView> {
  return (await post(`/api/weekly-reports/drafts/${encodeURIComponent(draftId)}/exports`, body)).json();
}

export async function approveWeeklyReportExport(id: string): Promise<WeeklyReportExportView> {
  return (await post(`/api/weekly-reports/exports/${encodeURIComponent(id)}/approve`, {})).json();
}

export async function retryWeeklyReportExport(id: string): Promise<WeeklyReportExportView> {
  return (await post(`/api/weekly-reports/exports/${encodeURIComponent(id)}/retry`, {})).json();
}

export async function cancelWeeklyReportExport(id: string): Promise<WeeklyReportExportView> {
  return (await post(`/api/weekly-reports/exports/${encodeURIComponent(id)}/cancel`, {})).json();
}

export async function addCalendarEvent(body: Omit<CalendarEventView, "id">): Promise<CalendarEventView[]> {
  return (await post("/api/pim/events", body)).json();
}
export async function deleteCalendarEvent(id: number): Promise<void> {
  await deleteJson(`/api/pim/events/${id}`);
}
export async function importCalendar(file: File): Promise<CalendarEventView[]> {
  const data = new FormData();
  data.append("file", file);
  const res = await fetch(`${getApiBase()}/api/pim/events/import`, {
    method: "POST", headers: authHeaders(), body: data,
  });
  if (!res.ok) throw await toRequestError(res, `导入失败（HTTP ${res.status}）`);
  return res.json();
}
export async function exportCalendar(): Promise<Blob> {
  const res = await fetch(`${getApiBase()}/api/pim/events/export`, { headers: authHeaders() });
  if (!res.ok) throw await toRequestError(res, `导出失败（HTTP ${res.status}）`);
  return res.blob();
}

export interface UserMailSettingsView {
  credentialMode: "EPHEMERAL" | "ENCRYPTED_AT_REST";
  imapHost: string;
  imapPort: number;
  smtpHost: string;
  smtpPort: number;
  username: string;
  passwordSet: boolean;
  passwordHint: string | null;
}

export async function fetchUserMailSettings(): Promise<UserMailSettingsView> {
  return (await getJson("/api/mail/settings")).json();
}

/** 迭代 23C：影响 Agent 执行的配置与基础设施状态（不含凭据和连接细节）。 */
export type CapabilityStatus = "READY" | "ACTION_REQUIRED" | "DEGRADED" | "UNAVAILABLE";

export interface SystemCapability {
  id: "model" | "database" | "knowledge" | "mcp" | "mail" | "workspace" | string;
  name: string;
  status: CapabilityStatus;
  message: string;
  action: string | null;
}

export interface SystemCapabilitySnapshot {
  capabilities: SystemCapability[];
}

export async function fetchSystemCapabilities(): Promise<SystemCapabilitySnapshot> {
  return (await getJson("/api/system/capabilities")).json();
}

export async function saveUserMailSettings(body: {
  credentialMode: UserMailSettingsView["credentialMode"];
  imapHost: string; imapPort: number; smtpHost: string; smtpPort: number;
  username: string; password: string | null;
}): Promise<UserMailSettingsView> {
  if (body.credentialMode === "EPHEMERAL") {
    if (body.password) {
      saveEphemeralMailCredential({
        imapHost: body.imapHost, imapPort: body.imapPort, smtpHost: body.smtpHost,
        smtpPort: body.smtpPort, username: body.username, password: body.password,
      });
    }
  } else {
    saveEphemeralMailCredential(null);
  }
  return (await putJson("/api/mail/settings", body)).json();
}

export async function testUserMailSettings(settings?: MailConnectionSettings): Promise<{ ok: boolean; message: string }> {
  return (await post("/api/mail/settings/test", settings ?? {})).json();
}

// ═══ 任务（与 OpenAPI schema 对齐：/api/task，详情与 SSE 事件同构）═══

/** 任务计划步骤（后端 TaskPlan.TaskStep 的投影）。 */
export interface TaskStep {
  id: number;
  description: string;
  /** 迭代 17A：document / pim / workspace / research / general。 */
  agent: string;
  /** 迭代 20B：Vatica 固定的受控 Skill 发布版本；通用角色可能为空。 */
  skillId?: string | null;
  skillVersion?: string | null;
  /** 迭代 23A：Planner 声明且服务端复核的工具需求。 */
  requiredTools?: string[];
  /** 迭代 23A：Skill 不匹配时的确定性回退原因。 */
  capabilityResolution?: string | null;
  needsApproval: boolean;
  approved: boolean;
  result: string | null;
  /** 依赖的步骤 id（迭代 6 波次并行；无=依赖上一步）。 */
  dependsOn?: number[];
  /** 迭代 17B：显式共享写资源键；同波重复声明会先进入冲突仲裁。 */
  writeResources?: string[];
}

/** 迭代 17C：Agent 模型绑定设置与解析状态（后端 AgentModelBindingService.SettingsView）。 */
export interface AgentBindingView {
  scope: "USER" | "ORG" | "PLATFORM";
  scopeRef: number;
  agentId: string;
  role: string;
  slotId: string | null;
  slotName: string | null;
  enabled: boolean;
  credentialAvailable: boolean;
  status: "READY" | "DISABLED" | "SLOT_MISSING" | "SLOT_DISABLED" | "CREDENTIAL_MISSING" | "FOLLOW_DEFAULT" | string;
}

export interface AgentBindingSlotOption {
  id: string;
  name: string;
  model: string;
  enabled: boolean;
  credentialAvailable: boolean;
  capabilities: string[];
}

export interface AgentBindingAgentOption {
  id: string;
  role: string;
  modelCapability: string;
}

export interface AgentBindingSettings {
  bindings: AgentBindingView[];
  slots: AgentBindingSlotOption[];
  agents: AgentBindingAgentOption[];
}

export async function fetchAgentBindings(): Promise<AgentBindingSettings> {
  return (await getJson("/api/models/agent-bindings")).json();
}

export async function saveAgentBinding(request: {
  scope: "USER" | "ORG" | "PLATFORM";
  agentId: string;
  slotId: string | null;
}): Promise<AgentBindingView> {
  return (await putJson("/api/models/agent-bindings", request)).json();
}

/** 迭代 20A：后端 SkillCatalogService.SkillVersionView。 */
export interface SkillResourceLimits {
  maxIterations: number;
  maxToolCalls: number;
  maxOutputChars: number;
}

export interface SkillVersionView {
  version: string;
  active: boolean;
  latest: boolean;
  tools: string[];
  permissions: string[];
  limits: SkillResourceLimits;
  releasedAt: string;
  checksum: string;
}

/** 迭代 20A：组织级 Skill 安装状态，发布版本本体不可变。 */
export interface SkillView {
  id: string;
  displayName: string;
  description: string;
  agentRole: string;
  activeVersion: string;
  latestVersion: string;
  previousVersion: string | null;
  enabled: boolean;
  manageable: boolean;
  canRollback: boolean;
  revision: number;
  updatedAt: string;
  tools: string[];
  permissions: string[];
  limits: SkillResourceLimits;
  versions: SkillVersionView[];
}

export async function fetchSkills(): Promise<SkillView[]> {
  return (await getJson("/api/skills")).json();
}

export async function setSkillEnabled(skillId: string, enabled: boolean): Promise<SkillView> {
  const action = enabled ? "enable" : "disable";
  return (await post(`/api/skills/${encodeURIComponent(skillId)}/${action}`, {})).json();
}

export async function activateSkillVersion(skillId: string, version: string): Promise<SkillView> {
  return (await putJson(`/api/skills/${encodeURIComponent(skillId)}/active-version`, { version })).json();
}

export async function rollbackSkill(skillId: string): Promise<SkillView> {
  return (await post(`/api/skills/${encodeURIComponent(skillId)}/rollback`, {})).json();
}

export interface RoleUsageTotal {
  agentId: string;
  role: string;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  requests: number;
  durationMs: number;
  costEstimate: number;
  taskCount: number;
  passedTasks: number;
  passRate: number | null;
}

export interface UsageToday {
  date: string;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  reasoningTokens: number;
  quota: number;
  persistedTokens: number;
  reservedTokens: number;
  byRole: Record<string, RoleUsageTotal>;
}

export async function fetchUsageToday(): Promise<UsageToday> {
  return (await getJson("/api/usage/today")).json();
}

/** 迭代 18：Legacy / AgentScope 任务质量与耗时基线。 */
export interface RuntimeReliabilityTotals {
  runtime: string;
  taskCount: number;
  completedTasks: number;
  passedTasks: number;
  failedTasks: number;
  cancelledTasks: number;
  multiAttemptTasks: number;
  passRate: number | null;
  averageScore: number | null;
  averageDurationMs: number | null;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  toolCalls: number;
  failedToolCalls: number;
}

export interface ReliabilityView {
  runtimes: RuntimeReliabilityTotals[];
}

export async function fetchReliabilityBaseline(): Promise<ReliabilityView> {
  return (await getJson("/api/usage/reliability")).json();
}

/** 迭代 18B：Legacy / AgentScope 固定评测任务集目录。 */
export interface BenchmarkCase {
  id: string;
  title: string;
  goal: string;
  expectedAgent: string;
  requiredTools: string[];
  requiresApproval: boolean;
  hasSideEffect: boolean;
  acceptance: string;
}

export async function fetchBenchmarkCases(): Promise<BenchmarkCase[]> {
  return (await getJson("/api/evaluation/benchmark-cases")).json();
}

export interface EvaluationThresholds {
  minSamplesPerCase: number;
  minPassRate: number;
  minAverageScore: number;
  maxFailedToolRate: number;
}

export interface CaseRuntimeResult {
  caseId: string;
  title: string;
  runtime: "agentscope" | "legacy";
  taskCount: number;
  terminalSamples: number;
  passedTasks: number;
  failedTasks: number;
  cancelledTasks: number;
  passRate: number | null;
  averageScore: number | null;
  averageDurationMs: number | null;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  costEstimate: number;
  toolCalls: number;
  failedToolCalls: number;
}

export interface RuntimeEvaluationGate {
  runtime: "agentscope" | "legacy";
  status: "PENDING" | "PASS" | "FAIL";
  coveredCases: number;
  totalCases: number;
  terminalSamples: number;
  passRate: number | null;
  averageScore: number | null;
  failedToolRate: number | null;
  totalTokens: number;
  costEstimate: number;
  reasons: string[];
}

export interface EvaluationReport {
  generatedAt: string;
  thresholds: EvaluationThresholds;
  results: CaseRuntimeResult[];
  gates: RuntimeEvaluationGate[];
}

export async function fetchEvaluationReport(): Promise<EvaluationReport> {
  return (await getJson("/api/evaluation/report")).json();
}

export interface BlackboardEntry {
  id: string;
  type: "result" | "note" | "need-help" | "conflict";
  stepId: number;
  agent: string;
  author: string;
  content: string;
  resource: string | null;
  relatedStepIds: number[];
  status: "RECORDED" | "OPEN" | "PLANNER_RESOLVED" | "HUMAN_RESOLVED" | "BUDGET_EXHAUSTED";
  createdAt: string;
}

export interface TaskPlanView {
  steps?: TaskStep[];
  blackboard?: BlackboardEntry[];
  collaborationRevisionCount?: number;
  discoveryStepCount?: number;
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
  plan?: TaskPlanView | string;
  /** 迭代 13：服务重启中断后是否可"继续执行"。 */
  recoverable: boolean;
  /** 迭代 18：执行尝试次数。 */
  executionAttempt: number;
  /** 迭代 18：本次执行使用的运行时。 */
  executionRuntime: string | null;
  /** 迭代 18：最近一次执行心跳。 */
  lastHeartbeatAt: string | null;
  /** 迭代 18：恢复是否需要人工确认中断步骤。 */
  recoveryApprovalRequired: boolean;
  /** 迭代 18C：固定评测用例 id；普通任务为 null。 */
  benchmarkCaseId: string | null;
}

/** SSE 进度事件负载 = 完整任务快照 + 事件类型。 */
export type TaskEvent = TaskDetail & { type: string };

/** 任务概要（后端 TaskSummaryDto，最近任务列表用）。 */
export interface TaskSummary {
  id: string;
  goal: string;
  status: string;
  createdAt: string;
  benchmarkCaseId: string | null;
}

export async function fetchRecentTasks(): Promise<TaskSummary[]> {
  return (await getJson("/api/task")).json();
}

export async function createTask(
  goal: string,
  permission?: FilePermissionPolicy,
  credential?: EphemeralCredential,
  benchmarkCaseId?: string,
  idempotencyKey = crypto.randomUUID(),
): Promise<TaskDetail> {
  const res = await fetch(`${getApiBase()}/api/task`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey, ...authHeaders() },
    body: JSON.stringify({
      goal,
      permission,
      credential,
      mailCredential: getEphemeralMailCredential(),
      benchmarkCaseId,
    }),
  });
  if (!res.ok) throw await toRequestError(res, `请求失败（HTTP ${res.status}）`);
  return res.json();
}

export async function fetchTaskDetail(id: string): Promise<TaskDetail> {
  return (await getJson(`/api/task/${id}`)).json();
}

/** 任务工具调用 trace（迭代 15 I15-1，后端 TaskController.AgentTraceView）。 */
export interface AgentTraceView {
  id: string;
  stepId: number | null;
  traceId: string;
  agentId: string | null;
  role: string | null;
  skillId: string | null;
  skillVersion: string | null;
  skillPermissions: string[];
  toolName: string;
  inputSummary: string;
  outputSummary: string;
  outputLength: number;
  durationMs: number;
  status: "SUCCESS" | "FAILED";
  error: string | null;
  createdAt: string | null;
}

/** 迭代 15 I15-1：查询任务执行轨迹（脱敏摘要级，后端已按任务归属过滤）。 */
export async function fetchTaskTraces(taskId: string): Promise<AgentTraceView[]> {
  return (await getJson(`/api/task/${encodeURIComponent(taskId)}/traces`)).json();
}

// ═══ Agent 可观测性（迭代 21A/21B：Run/Span 诊断工作台）═══

export interface ObservabilitySpan {
  spanId: string;
  traceId: string;
  parentSpanId: string | null;
  runId: string;
  taskId: string | null;
  stepId: number | null;
  attempt: number;
  spanType: string;
  name: string;
  runtime: string | null;
  agentId: string | null;
  role: string | null;
  modelSlotId: string | null;
  skillId: string | null;
  skillVersion: string | null;
  status: "OPEN" | "SUCCESS" | "FAILED" | "CANCELLED" | string;
  startedAt: string;
  endedAt: string | null;
  durationMs: number;
  inputSummary: string | null;
  outputSummary: string | null;
  errorCode: string | null;
  errorSummary: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  totalTokens: number | null;
  reasoningTokens: number | null;
  contextFillRatio: number | null;
  costEstimate: number | null;
  judgeScore: number | null;
  judgeVerdict: string | null;
}

export interface ObservabilityRun {
  traceId: string;
  runId: string;
  taskId: string | null;
  status: string;
  startedAt: string | null;
  endedAt: string | null;
  durationMs: number;
  runtime: string | null;
  attempt: number;
  spanCount: number;
  failedSpanCount: number;
  totalTokens: number | null;
  costEstimate: number | null;
  judgeScore: number | null;
  judgeVerdict: string | null;
}

export interface ObservabilityOverview {
  windowStart: string | null;
  windowEnd: string | null;
  runCount: number;
  successCount: number;
  failedCount: number;
  successRate: number;
  p50DurationMs: number;
  p95DurationMs: number;
  totalTokens: number;
  totalCost: number;
  failedSpanCount: number;
  droppedSpanWrites: number;
  recentRuns: ObservabilityRun[];
}

export interface ObservabilityRunQuery {
  from?: string;
  to?: string;
  traceId?: string;
  taskId?: string;
  status?: string;
  spanType?: string;
  name?: string;
  runtime?: string;
  agentId?: string;
  modelSlotId?: string;
  skillId?: string;
  errorCode?: string;
  judgeVerdict?: string;
  minDurationMs?: number;
  maxDurationMs?: number;
  minJudgeScore?: number;
  page?: number;
  size?: number;
  sortBy?: string;
  direction?: "asc" | "desc";
}

export interface ObservabilityQueryAggregate {
  runCount: number;
  successCount: number;
  failedCount: number;
  successRate: number;
  spanCount: number;
  failedSpanCount: number;
  totalTokens: number;
  totalCost: number;
  p50DurationMs: number;
  p95DurationMs: number;
}

export interface ObservabilityRunQueryPage {
  items: ObservabilityRun[];
  page: number;
  size: number;
  totalRuns: number;
  totalPages: number;
  aggregate: ObservabilityQueryAggregate;
  sortBy: string;
  direction: string;
}

export interface ObservabilityDiagnosisFinding {
  kind: "FAILURE" | "SLOW" | "RETRY" | "QUALITY" | "COST" | string;
  severity: "ERROR" | "WARN" | "INFO" | string;
  title: string;
  evidence: string;
  traceId: string;
  spanId: string | null;
  taskId: string | null;
}

export interface ObservabilityDiagnosisReport {
  scope: string;
  spanCount: number;
  runCount: number;
  findings: ObservabilityDiagnosisFinding[];
}

export async function fetchObservabilityOverview(limit = 20): Promise<ObservabilityOverview> {
  return (await getJson("/api/observability/overview?limit=" + limit)).json();
}

export async function fetchObservabilityRuns(limit = 50): Promise<ObservabilityRun[]> {
  return (await getJson("/api/observability/runs?limit=" + limit)).json();
}

export async function fetchObservabilityRunQuery(query: ObservabilityRunQuery = {}): Promise<ObservabilityRunQueryPage> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  }
  return (await getJson("/api/observability/spans?" + params.toString())).json();
}

export async function fetchObservabilityDiagnostics(query: Pick<ObservabilityRunQuery, "from" | "to" | "traceId" | "taskId" | "status" | "spanType" | "runtime" | "agentId" | "modelSlotId" | "skillId" | "errorCode" | "judgeVerdict"> = {}): Promise<ObservabilityDiagnosisReport> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  }
  return (await getJson("/api/observability/diagnostics?" + params.toString())).json();
}

export async function exportObservabilityDiagnostics(traceId: string): Promise<Blob> {
  const res = await fetch(`${getApiBase()}/api/observability/diagnostics/export?traceId=${encodeURIComponent(traceId)}`, { headers: authHeaders() });
  if (!res.ok) throw await toRequestError(res, `诊断报告导出失败（HTTP ${res.status}）`);
  return res.blob();
}

export async function fetchObservabilityTrace(traceId: string): Promise<ObservabilitySpan[]> {
  return (await getJson("/api/observability/traces/" + encodeURIComponent(traceId))).json();
}

export async function fetchObservabilityTask(taskId: string): Promise<ObservabilitySpan[]> {
  return (await getJson("/api/observability/tasks/" + encodeURIComponent(taskId))).json();
}

/** 任务动作：approve（审批计划/步骤）/ rework（人工返工）/ cancel（终止）/ resume（继续执行，迭代 13）。 */
export async function taskAction(id: string, action: "approve" | "rework" | "cancel" | "resume"): Promise<TaskDetail> {
  return (await post(`/api/task/${id}/${action}`, {})).json();
}

/** 迭代 17B：HumanAgent 写 note，服务端按当前用户和任务归属落黑板。 */
export async function addTaskNote(id: string, content: string): Promise<TaskDetail> {
  return (await post(`/api/task/${encodeURIComponent(id)}/notes`, { content })).json();
}

/**
 * 订阅任务进度事件（迭代 16 I16-4）：
 * 使用统一 fetch-SSE，自动携带 JWT、Last-Event-ID，断线后回放并按 id 去重。
 */
export function subscribeTaskEvents(
  id: string,
  onEvent: (e: TaskEvent) => void,
  onPermission?: (e: FilePermissionRequest) => void,
  onBlackboard?: (entry: BlackboardEntry) => void,
): () => void {
  const controller = new AbortController();
  let cancelled = false;
  void (async () => {
    try {
      for await (const event of fetchSse<unknown>(`/api/task/${encodeURIComponent(id)}/events`, {
        headers: authHeaders(),
      }, controller.signal, true)) {
        if (event.type === "permission_request") {
          onPermission?.(event.data as FilePermissionRequest);
        } else if (event.type === "blackboard_entry") {
          onBlackboard?.((event.data as { taskId: string; entry: BlackboardEntry }).entry);
        } else if (event.type === "task_snapshot" || event.type === "task") {
          onEvent(event.data as TaskEvent);
        }
      }
    } catch (e) {
      if (cancelled || controller.signal.aborted) return;   // 主动取消不告警
      console.warn("任务进度订阅中断", e);
    }
  })();
  return () => {
    cancelled = true;
    controller.abort();
  };
}
