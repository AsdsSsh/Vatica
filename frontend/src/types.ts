/** 会话与消息类型（前端本地状态；后端已有会话记忆按 sessionId 关联）。 */
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  /** 流式中断/出错时的状态说明（正常完成不设）。 */
  note?: string;
}

export interface ChatSession {
  id: string;
  /** 会话标题：取首条用户消息前 20 字。 */
  title: string;
  messages: ChatMessage[];
  createdAt: number;
}

export function createSession(): ChatSession {
  return {
    id: crypto.randomUUID(),
    title: "新会话",
    messages: [],
    createdAt: Date.now(),
  };
}
