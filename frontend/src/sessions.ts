/**
 * 会话本地持久化（迭代 12 I12-5）：localStorage 快照 + 上限裁剪 + 启动恢复。
 * 桌面应用重启后会话列表与消息不丢；超限按"最多会话数 / 单会话消息数 / 单条消息字符数"裁剪。
 */
import { createSession, type ChatMessage, type ChatSession } from "./types";

const STORAGE_KEY = "vatica.sessions.v1";
const MAX_SESSIONS = 50;
const MAX_MESSAGES_PER_SESSION = 200;
const MAX_MESSAGE_CHARS = 20_000;

function sanitizeMessage(raw: unknown): ChatMessage | null {
  if (!raw || typeof raw !== "object") return null;
  const m = raw as Partial<ChatMessage>;
  if (typeof m.id !== "string" || (m.role !== "user" && m.role !== "assistant")) return null;
  const content = typeof m.content === "string" ? m.content.slice(0, MAX_MESSAGE_CHARS) : "";
  const message: ChatMessage = { id: m.id, role: m.role, content };
  if (typeof m.note === "string" && m.note) message.note = m.note.slice(0, 500);
  return message;
}

function sanitizeSession(raw: unknown): ChatSession | null {
  if (!raw || typeof raw !== "object") return null;
  const s = raw as Partial<ChatSession>;
  if (typeof s.id !== "string" || !s.id) return null;
  const messages = Array.isArray(s.messages)
    ? s.messages.map(sanitizeMessage).filter((m): m is ChatMessage => m !== null).slice(-MAX_MESSAGES_PER_SESSION)
    : [];
  return {
    id: s.id,
    title: typeof s.title === "string" && s.title.trim() ? s.title.slice(0, 80) : "新会话",
    messages,
    createdAt: typeof s.createdAt === "number" ? s.createdAt : Date.now(),
  };
}

export function loadSessions(): ChatSession[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [createSession()];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [createSession()];
    const sessions = parsed.map(sanitizeSession).filter((s): s is ChatSession => s !== null);
    const deduped = sessions.filter((s, i) => sessions.findIndex((x) => x.id === s.id) === i);
    const trimmed = deduped.slice(0, MAX_SESSIONS);
    return trimmed.length > 0 ? trimmed : [createSession()];
  } catch {
    return [createSession()];
  }
}

export function saveSessions(sessions: ChatSession[]): void {
  try {
    const trimmed = sessions.slice(0, MAX_SESSIONS);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // 隐私模式等 localStorage 不可用时忽略
  }
}
