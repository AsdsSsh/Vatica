import { useEffect, useRef, useState } from "react";
import { Button, Flex, Input, Select, Spin, Tag, Typography } from "antd";
import { SendOutlined, StopOutlined } from "@ant-design/icons";
import type { ChatMessage, ChatSession } from "../types";
import { fetchModels, streamChat, type ModelInfo } from "../api";
import Markdown from "./Markdown";

/**
 * 中栏：对话区（迭代 6 I6-4/I6-5；迭代 7 I7-5 模型选择器）——
 * 消息列表 + Markdown 渲染 + SSE 流式打字机 + 停止按钮 + 模型选择。
 */
interface Props {
  session: ChatSession;
  streaming: boolean;
  onStreamingChange: (v: boolean) => void;
  /** 追加消息（含创建流式中的助手消息）。 */
  onAppendMessage: (msg: ChatMessage) => void;
  /** 基于旧值更新消息（流式追加用函数式更新）。 */
  onUpdateMessage: (id: string, update: (m: ChatMessage) => ChatMessage) => void;
}

export default function ChatPanel({
  session,
  streaming,
  onStreamingChange,
  onAppendMessage,
  onUpdateMessage,
}: Props) {
  const [input, setInput] = useState("");
  const [model, setModel] = useState("deepseek");
  const [models, setModels] = useState<ModelInfo[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [session.messages]);

  // 模型清单（迭代 7 I7-5）
  useEffect(() => {
    fetchModels()
      .then((list) => {
        setModels(list);
        const firstConfigured = list.find((m) => m.configured);
        if (firstConfigured) setModel(firstConfigured.id);
      })
      .catch(() => {
        // 后端未启动时保持默认模型，不打扰用户
      });
  }, []);

  async function send() {
    const text = input.trim();
    if (!text || streaming) return;
    setInput("");
    onAppendMessage({ id: crypto.randomUUID(), role: "user", content: text });
    const assistantId = crypto.randomUUID();
    onAppendMessage({ id: assistantId, role: "assistant", content: "" });
    onStreamingChange(true);

    const controller = new AbortController();
    abortRef.current = controller;
    try {
      for await (const chunk of streamChat(text, session.id, model, controller.signal)) {
        onUpdateMessage(assistantId, (m) => ({
          ...m,
          content: m.content + chunk,
        }));
      }
    } catch (e) {
      const reason =
        (e as Error)?.name === "AbortError"
          ? "（已停止）"
          : `（连接中断：${(e as Error)?.message ?? "未知错误"}）`;
      onUpdateMessage(assistantId, (m) => ({ ...m, note: reason }));
    } finally {
      abortRef.current = null;
      onStreamingChange(false);
    }
  }

  function stop() {
    abortRef.current?.abort();
  }

  return (
    <Flex vertical style={{ height: "100%" }}>
      {/* 顶栏：会话标题 + 模型选择器 */}
      <Flex
        justify="space-between"
        align="center"
        style={{ padding: "8px 16px", borderBottom: "1px solid #f0f0f0" }}
      >
        <Typography.Text ellipsis={{ tooltip: session.title }} strong style={{ maxWidth: "55%" }}>
          {session.title}
        </Typography.Text>
        <Select
          size="small"
          style={{ width: 220 }}
          value={model}
          onChange={setModel}
          options={models.map((m) => ({
            value: m.id,
            label: m.configured ? m.name : `${m.name}（未配置）`,
            disabled: !m.configured,
          }))}
        />
      </Flex>

      {/* 消息区 */}
      <div style={{ flex: 1, overflowY: "auto", padding: 16 }}>
        {session.messages.length === 0 && (
          <div style={{ textAlign: "center", marginTop: "25vh", color: "#999" }}>
            <Typography.Title level={4} style={{ color: "#999" }}>
              Vatica 个人 AI 助理
            </Typography.Title>
            <Typography.Text type="secondary">
              一句话安排日程 / 生成周报 Word / 查天气——开始对话吧
            </Typography.Text>
          </div>
        )}
        {session.messages.map((m) => (
          <div
            key={m.id}
            style={{
              display: "flex",
              justifyContent: m.role === "user" ? "flex-end" : "flex-start",
              marginBottom: 12,
            }}
          >
            <div
              style={{
                maxWidth: "82%",
                borderRadius: 10,
                padding: "8px 14px",
                background: m.role === "user" ? "#1677ff" : "#f4f4f5",
                color: m.role === "user" ? "#fff" : "inherit",
              }}
            >
              {m.role === "assistant" ? (
                <Markdown content={m.content} />
              ) : (
                <span style={{ whiteSpace: "pre-wrap" }}>{m.content}</span>
              )}
              {m.note && <Tag color="orange" style={{ marginTop: 4 }}>{m.note}</Tag>}
            </div>
          </div>
        ))}
        {streaming && (
          <div style={{ display: "flex", justifyContent: "flex-start", marginBottom: 12 }}>
            <Spin size="small" style={{ marginRight: 8 }} />
            <Typography.Text type="secondary">正在生成…</Typography.Text>
          </div>
        )}
        <div ref={endRef} />
      </div>

      {/* 输入区 */}
      <div style={{ padding: 12, borderTop: "1px solid #f0f0f0" }}>
        <Flex gap={8} align="end">
          <Input.TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="给 Vatica 发消息（Enter 发送，Shift+Enter 换行）"
            autoSize={{ minRows: 1, maxRows: 5 }}
            disabled={streaming}
            onPressEnter={(e) => {
              if (!e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
          />
          {streaming ? (
            <Button danger icon={<StopOutlined />} onClick={stop}>
              停止
            </Button>
          ) : (
            <Button type="primary" icon={<SendOutlined />} onClick={send} disabled={!input.trim()}>
              发送
            </Button>
          )}
        </Flex>
      </div>
    </Flex>
  );
}
