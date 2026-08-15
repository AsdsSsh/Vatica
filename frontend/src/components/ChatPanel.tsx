import { useEffect, useRef, useState } from "react";
import { App, Button, Checkbox, Flex, Input, Modal, Select, Space, Spin, Tag, Tooltip, Typography } from "antd";
import {
  ApiOutlined,
  SafetyCertificateOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
} from "@ant-design/icons";
import type { ChatMessage, ChatSession } from "../types";
import {
  approvePermissionRequest,
  denyPermissionRequest,
  fetchModels,
  streamChat,
  type FilePermissionRequest,
  type ModelInfo,
} from "../api";
import { loadPermissionPolicy, rememberWorkspaceRoot } from "../permissions";
import Markdown from "./Markdown";
import ModelSettings from "./ModelSettings";
import ServerSettings from "./ServerSettings";
import FilePermissionSettings from "./FilePermissionSettings";

/**
 * 中栏：对话区（迭代 6 I6-4/I6-5；迭代 7 I7-5 模型选择器；
 * 迭代 8.5 模型设置入口 + 配置保存后即时刷新选择器）——
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
  const { message } = App.useApp();
  const [input, setInput] = useState("");
  const [model, setModel] = useState("deepseek");
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [serverSettingsOpen, setServerSettingsOpen] = useState(false);
  const [permissionSettingsOpen, setPermissionSettingsOpen] = useState(false);
  const [permissionRequest, setPermissionRequest] = useState<FilePermissionRequest | null>(null);
  const [permissionDeciding, setPermissionDeciding] = useState(false);
  const [permissionRemember, setPermissionRemember] = useState(true);
  const permissionResolveRef = useRef<((approved: boolean) => void) | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [session.messages]);

  // 模型清单（迭代 7 I7-5；迭代 8.5 设置保存后刷新；迭代 10 I10-8 保存设置不重置已选模型）
  function loadModels() {
    fetchModels()
      .then((list) => {
        setModels(list);
        setModel((prev) => {
          if (list.some((m) => m.id === prev && m.configured)) return prev;
          const firstConfigured = list.find((m) => m.configured);
          return firstConfigured ? firstConfigured.id : prev;
        });
      })
      .catch(() => {
        // 后端未启动时保持默认模型，不打扰用户
      });
  }
  useEffect(loadModels, []);

  /** 展示权限弹窗并等待用户决定（streamChat 暂停在此处，后端工具同步等待）。 */
  function askPermission(request: FilePermissionRequest): Promise<boolean> {
    setPermissionRemember(true);
    setPermissionRequest(request);
    return new Promise((resolve) => {
      permissionResolveRef.current = resolve;
    });
  }

  async function decidePermission(approved: boolean) {
    const request = permissionRequest;
    if (!request) return;
    setPermissionDeciding(true);
    try {
      if (approved && permissionRemember) {
        rememberWorkspaceRoot(request.path, request.access);
      }
      if (approved) {
        await approvePermissionRequest(request.requestId, permissionRemember);
      } else {
        await denyPermissionRequest(request.requestId);
      }
      permissionResolveRef.current?.(approved);
    } catch (e) {
      message.error(`权限请求处理失败：${(e as Error).message}`);
      permissionResolveRef.current?.(false);
    } finally {
      permissionResolveRef.current = null;
      setPermissionRequest(null);
      setPermissionDeciding(false);
    }
  }

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
      for await (const event of streamChat(
        text,
        session.id,
        loadPermissionPolicy(),
        model,
        controller.signal,
      )) {
        if (event.kind === "text") {
          onUpdateMessage(assistantId, (m) => ({
            ...m,
            content: m.content + event.content,
          }));
        } else {
          // 权限请求：等待用户决定；后端工具调用保持阻塞，批准/拒绝后模型继续
          const approved = await askPermission(event.request);
          if (!approved) {
            onUpdateMessage(assistantId, (m) => ({
              ...m,
              note: "（已拒绝文件访问授权）",
            }));
          }
        }
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
    permissionResolveRef.current?.(false);
    permissionResolveRef.current = null;
    setPermissionRequest(null);
    abortRef.current?.abort();
  }

  return (
    <Flex vertical style={{ height: "100%" }}>
      {/* 顶栏：会话标题 + 模型选择器 + 模型设置 */}
      <Flex
        justify="space-between"
        align="center"
        style={{ padding: "8px 16px", borderBottom: "1px solid #f0f0f0" }}
      >
        <Typography.Text ellipsis={{ tooltip: session.title }} strong style={{ maxWidth: "45%" }}>
          {session.title}
        </Typography.Text>
        <Flex gap={8} align="center">
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
          <Tooltip title="模型设置">
            <Button
              size="small"
              icon={<SettingOutlined />}
              onClick={() => setSettingsOpen(true)}
            />
          </Tooltip>
          <Tooltip title="文件权限与工作区">
            <Button
              size="small"
              icon={<SafetyCertificateOutlined />}
              onClick={() => setPermissionSettingsOpen(true)}
            />
          </Tooltip>
          <Tooltip title="服务设置（后端接口地址）">
            <Button
              size="small"
              icon={<ApiOutlined />}
              onClick={() => setServerSettingsOpen(true)}
            />
          </Tooltip>
        </Flex>
      </Flex>

      <ModelSettings
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onSaved={loadModels}
      />
      <ServerSettings
        open={serverSettingsOpen}
        onClose={() => setServerSettingsOpen(false)}
      />
      <FilePermissionSettings
        open={permissionSettingsOpen}
        onClose={() => setPermissionSettingsOpen(false)}
      />

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

      {/* 文件权限请求弹窗（迭代 11） */}
      <Modal
        open={permissionRequest !== null}
        title="文件访问需要授权"
        onCancel={() => decidePermission(false)}
        footer={
          <Space>
            <Button danger onClick={() => decidePermission(false)}>
              拒绝
            </Button>
            <Button
              type="primary"
              loading={permissionDeciding}
              onClick={() => decidePermission(true)}
            >
              允许
            </Button>
          </Space>
        }
      >
        {permissionRequest && (
          <div>
            <Typography.Paragraph>
              Agent 请求<b>{permissionRequest.access === "WRITE" ? "写入" : "读取"}</b>以下路径：
            </Typography.Paragraph>
            <Typography.Paragraph code copyable style={{ wordBreak: "break-all" }}>
              {permissionRequest.path}
            </Typography.Paragraph>
            <Typography.Paragraph type="secondary">
              {permissionRequest.description}
            </Typography.Paragraph>
            <Checkbox
              checked={permissionRemember}
              onChange={(e) => setPermissionRemember(e.target.checked)}
            >
              记住授权，以后不再询问该目录
            </Checkbox>
          </div>
        )}
      </Modal>
    </Flex>
  );
}
