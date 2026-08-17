import { useCallback, useEffect, useRef, useState } from "react";
import {
  Alert,
  App,
  Button,
  Flex,
  Input,
  Select,
  Spin,
  Switch,
  Tag,
  Tooltip,
  Typography,
  theme,
} from "antd";
import {
  ApiOutlined,
  CheckCircleOutlined,
  CloudOutlined,
  CopyOutlined,
  ExclamationCircleOutlined,
  GlobalOutlined,
  LoadingOutlined,
  MoonOutlined,
  PicLeftOutlined,
  PicRightOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  SunOutlined,
  UserOutlined,
} from "@ant-design/icons";
import type { TextAreaRef } from "antd/es/input/TextArea";
import type { ChatMessage, ChatSession } from "../types";
import {
  approvePermissionRequest,
  denyPermissionRequest,
  fetchModels,
  fetchUserModelSlots,
  getEphemeralUserModelKey,
  streamChat,
  AUTH_EXPIRED_EVENT,
  isAuthExpiredError,
  type EphemeralCredential,
  type FilePermissionRequest,
  type ModelInfo,
  type ToolActivity,
  type UsageSummary,
  type UserModelSlotView,
} from "../api";
import { loadPermissionPolicy } from "../permissions";
import { useBackendStatus } from "../backendStatus";
import { useTheme } from "../theme";
import { useAuth } from "../auth";
import { accountStorageScope } from "../accountScope";
import Markdown from "./Markdown";
import VaticaMark from "./VaticaMark";
import ModelSettings from "./ModelSettings";
import ServerSettings from "./ServerSettings";
import FilePermissionSettings from "./FilePermissionSettings";
import PermissionRequestModal from "./PermissionRequestModal";
import AuthPanel from "./AuthPanel";
import UserModelsPanel from "./UserModelsPanel";
import IntegrationSettingsPanel from "./IntegrationSettingsPanel";
import PersonalWorkspacePanel from "./PersonalWorkspacePanel";

/**
 * 中栏：对话区（迭代 6 I6-4/I6-5；迭代 12 I12-2/I12-3 体验升级）——
 * 消息列表 + Markdown 渲染 + SSE 流式打字机 + 停止按钮 + 模型选择。
 * 迭代 12 新增：后端连接横幅、输入法守卫、智能滚动（不拽底）、首 token 输入中指示、
 * 空状态建议卡、消息复制、主题切换、侧栏折叠。
 */
interface Props {
  session: ChatSession;
  streaming: boolean;
  leftCollapsed: boolean;
  rightCollapsed: boolean;
  onToggleLeft: () => void;
  onToggleRight: () => void;
  onStreamingChange: (v: boolean) => void;
  /** 追加消息（含创建流式中的助手消息）。 */
  onAppendMessage: (msg: ChatMessage) => void;
  /** 基于旧值更新消息（流式追加用函数式更新）。 */
  onUpdateMessage: (id: string, update: (m: ChatMessage) => ChatMessage) => void;
}

const MODEL_STORAGE_KEY = "vatica.model";

function modelStorageKey(): string {
  return `${MODEL_STORAGE_KEY}.${accountStorageScope()}`;
}

const SUGGESTIONS: { title: string; prompt: string }[] = [
  { title: "整理下周日程", prompt: "帮我查看下周的日历，并为每场会议创建准备待办" },
  { title: "生成周报 Word", prompt: "读取工作区里的本周工作记录，生成一份周报 Word 和统计 Excel" },
  { title: "查天气", prompt: "用 MCP 查一下北京未来三天的天气" },
  { title: "看看工作区", prompt: "列出当前工作区根，并说说里面都有什么" },
];

function readSavedModel(): string | undefined {
  try {
    return localStorage.getItem(modelStorageKey()) ?? undefined;
  } catch {
    return undefined;
  }
}

export default function ChatPanel({
  session,
  streaming,
  leftCollapsed,
  rightCollapsed,
  onToggleLeft,
  onToggleRight,
  onStreamingChange,
  onAppendMessage,
  onUpdateMessage,
}: Props) {
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const { isDark, setMode } = useTheme();
  const { online, refresh: refreshBackend } = useBackendStatus();
  const { status: authStatus, user: authUser } = useAuth();

  const [input, setInput] = useState("");
  const [model, setModel] = useState<string | undefined>(readSavedModel);
  const [deepThinking, setDeepThinking] = useState(false);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [userSlots, setUserSlots] = useState<UserModelSlotView[]>([]);
  const [typing, setTyping] = useState(false);
  const [streamingMessageId, setStreamingMessageId] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [serverSettingsOpen, setServerSettingsOpen] = useState(false);
  const [permissionSettingsOpen, setPermissionSettingsOpen] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [userModelsOpen, setUserModelsOpen] = useState(false);
  const [integrationOpen, setIntegrationOpen] = useState(false);
  const [personalWorkspaceOpen, setPersonalWorkspaceOpen] = useState(false);
  const [permissionRequests, setPermissionRequests] = useState<FilePermissionRequest[]>([]);
  const [permissionDeciding, setPermissionDeciding] = useState(false);
  const [permissionRemember, setPermissionRemember] = useState(true);
  const [showJumpToBottom, setShowJumpToBottom] = useState(false);
  const [toolActivity, setToolActivity] = useState<ToolActivity | null>(null);
  // 迭代 15 I15-7：思考过程折叠展示（reasoning SSE 事件累加）
  const [reasoning, setReasoning] = useState("");
  // 迭代 15 I15-13：本轮 token 用量（usage SSE 收尾事件）
  const [usage, setUsage] = useState<UsageSummary | null>(null);

  /** 迭代 13.5：并行流可能同时提出多个权限请求，按 requestId 保存每个等待决定。 */
  const permissionResolveRef = useRef<Map<string, (approved: boolean) => void>>(new Map());
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<TextAreaRef>(null);
  const autoScrollRef = useRef(true);
  /** 迭代 14.5：账号身份变化时清掉上一账号的用户模型/模型选择等内存态。 */
  const previousAuthKey = useRef<string | null>(null);

  /** 智能滚动（U2）：只有原本就在底部附近才跟随新内容；用户上翻历史不拽回。 */
  function scrollToBottom(behavior: ScrollBehavior) {
    const el = scrollRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior });
  }

  useEffect(() => {
    if (autoScrollRef.current) {
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    }
  }, [session.messages, typing]);

  function handleScroll() {
    const el = scrollRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    autoScrollRef.current = nearBottom;
    setShowJumpToBottom(!nearBottom);
  }

  // 模型清单：挂载时与后端恢复在线时加载；后端离线由连接横幅承担提示，不弹错误
  const loadModels = useCallback(() => {
    fetchModels()
      .then((list) => {
        setModels(list);
        setModel((prev) => {
          if (prev && list.some((m) => m.id === prev && m.configured)) return prev;
          const firstConfigured = list.find((m) => m.configured);
          return firstConfigured ? firstConfigured.id : prev;
        });
      })
      .catch(() => {
        // 连接状态横幅已提示；保留上次成功加载的清单
      });
    fetchUserModelSlots()
      .then(setUserSlots)
      .catch(() => {
        // 未登录/后端未启用鉴权时忽略，不影响内置模型
      });
  }, []);

  const authKey =
    authStatus === "authenticated" || authStatus === "local"
      ? `${authUser?.orgId ?? "-"}:${authUser?.userId ?? "-"}`
      : authStatus;

  // 迭代 14.5：账号切换/退出时清空上一账号的用户模型内存态；登录/本地模式自动重载
  useEffect(() => {
    if (previousAuthKey.current !== null && previousAuthKey.current !== authKey) {
      setUserSlots([]);
      setModel((prev) => (prev?.startsWith("user:") ? undefined : prev));
    }
    previousAuthKey.current = authKey;
    if (online && authStatus !== "loading" && authStatus !== "anonymous") {
      void loadModels();
    }
  }, [authKey, authStatus, online, loadModels]);

  // 迭代 14.5：401 统一收口后的全局提示只在此处弹一次，各请求组件不再重复弹错
  useEffect(() => {
    const onExpired = (event: Event) => {
      const detail = (event as CustomEvent<{ message?: string }>).detail;
      message.error(detail?.message ?? "登录已过期，请重新登录。");
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, onExpired);
  }, [message]);

  /** 展示权限弹窗并等待用户决定（streamChat 暂停在此处，后端工具同步等待）。 */
  function askPermission(request: FilePermissionRequest): Promise<boolean> {
    setPermissionRemember(true);
    setPermissionRequests((prev) =>
      prev.some((p) => p.requestId === request.requestId) ? prev : [...prev, request],
    );
    return new Promise((resolve) => {
      permissionResolveRef.current.set(request.requestId, resolve);
    });
  }

  async function decidePermission(approved: boolean) {
    const request = permissionRequests[0];
    if (!request) return;
    setPermissionDeciding(true);
    try {
      if (approved) {
        await approvePermissionRequest(request.requestId, permissionRemember);
      } else {
        await denyPermissionRequest(request.requestId);
      }
      permissionResolveRef.current.get(request.requestId)?.(approved);
    } catch (e) {
      message.error(`权限请求处理失败：${(e as Error).message}`);
      permissionResolveRef.current.get(request.requestId)?.(false);
    } finally {
      permissionResolveRef.current.delete(request.requestId);
      setPermissionRequests((prev) => prev.filter((p) => p.requestId !== request.requestId));
      setPermissionDeciding(false);
    }
  }

  async function copyMessage(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      message.success("已复制");
    } catch {
      message.error("复制失败，请手动选择复制");
    }
  }

  /** 迭代 13.5：选中"仅本机"用户模型时，从本机取 key 组装请求级 credential（后端与 model 二选一）。 */
  function ephemeralCredentialFor(modelId: string | undefined): {
    credential: EphemeralCredential | undefined;
    requestModel: string | undefined;
  } {
    if (!modelId?.startsWith("user:")) {
      return { credential: undefined, requestModel: modelId };
    }
    const slot = userSlots.find((s) => `user:${s.id}` === modelId);
    if (!slot || slot.credentialMode !== "EPHEMERAL") {
      return { credential: undefined, requestModel: modelId };
    }
    const apiKey = getEphemeralUserModelKey(slot.id);
    if (!apiKey) {
      return { credential: undefined, requestModel: modelId };
    }
    return {
      credential: {
        protocol: slot.protocol,
        baseUrl: slot.baseUrl,
        model: slot.model,
        temperature: slot.temperature,
        apiKey,
      },
      requestModel: undefined,
    };
  }

  async function send() {
    const text = input.trim();
    if (!text || streaming || !online) return;
    if (authStatus === "anonymous") {
      message.error("请先在右上角账号中登录，再使用云端能力。");
      return;
    }
    const selectedSlot = model?.startsWith("user:")
      ? userSlots.find((s) => `user:${s.id}` === model)
      : undefined;
    if (selectedSlot?.credentialMode === "EPHEMERAL" && !getEphemeralUserModelKey(selectedSlot.id)) {
      message.error("该模型是仅本机模式：请先在「我的模型」中编辑并填写 API Key，Key 只保存在本机。");
      return;
    }
    const { credential, requestModel } = ephemeralCredentialFor(model);
    setInput("");
    autoScrollRef.current = true;
    setShowJumpToBottom(false);
    setToolActivity(null);
    setReasoning("");
    setUsage(null);
    onAppendMessage({ id: crypto.randomUUID(), role: "user", content: text });
    const assistantId = crypto.randomUUID();
    onAppendMessage({ id: assistantId, role: "assistant", content: "" });
    setStreamingMessageId(assistantId);
    setTyping(true);
    onStreamingChange(true);

    const controller = new AbortController();
    abortRef.current = controller;
    try {
      for await (const event of streamChat(
        text,
        session.id,
        loadPermissionPolicy(),
        requestModel,
        controller.signal,
        credential,
        deepThinking,
      )) {
        if (event.kind === "text") {
          if (typing) setTyping(false);
          onUpdateMessage(assistantId, (m) => ({
            ...m,
            content: m.content + event.content,
          }));
        } else if (event.kind === "tool") {
          setToolActivity(event.activity);
        } else if (event.kind === "reasoning") {
          setReasoning((prev) => prev + event.content);
        } else if (event.kind === "usage") {
          setUsage(event.usage);
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
          : isAuthExpiredError(e)
            ? "（登录已过期，请重新登录）"
            : `（连接中断：${(e as Error)?.message ?? "未知错误"}）`;
      onUpdateMessage(assistantId, (m) => ({ ...m, note: reason }));
    } finally {
      abortRef.current = null;
      setStreamingMessageId(null);
      setTyping(false);
      onStreamingChange(false);
    }
  }

  function stop() {
    // 停止当前流：所有排队中的权限请求一并按拒绝收尾，避免 Promise 永久悬挂
    permissionResolveRef.current.forEach((resolve) => resolve(false));
    permissionResolveRef.current.clear();
    setPermissionRequests([]);
    abortRef.current?.abort();
  }

  const lastMessages = session.messages;

  return (
    <Flex vertical style={{ height: "100%" }}>
      {/* 顶栏：侧栏折叠 + 会话标题 + 模型选择器 + 设置 */}
      <Flex
        justify="space-between"
        align="center"
        gap={8}
        style={{ padding: "8px 16px", borderBottom: "1px solid var(--vatica-border)" }}
      >
        <Flex gap={4} align="center" style={{ minWidth: 0 }}>
          <Tooltip title={leftCollapsed ? "展开会话栏" : "收起会话栏"}>
            <Button
              size="small"
              type="text"
              aria-label="切换会话栏"
              icon={<PicLeftOutlined />}
              style={{ color: leftCollapsed ? token.colorPrimary : undefined }}
              onClick={onToggleLeft}
            />
          </Tooltip>
          <Typography.Text
            ellipsis={{ tooltip: session.title }}
            strong
            style={{ maxWidth: "36%", fontSize: 13 }}
          >
            {session.title}
          </Typography.Text>
        </Flex>
        <Flex gap={6} align="center" style={{ flexShrink: 0 }}>
          <Select
            size="small"
            style={{ width: 210 }}
            placeholder={models.length ? "选择模型" : "未连接后端"}
            value={models.length ? model : undefined}
            onChange={(v) => {
              const next = String(v);
              setModel(next);
              try {
                localStorage.setItem(modelStorageKey(), next);
              } catch {
                // 忽略
              }
            }}
            options={[
              {
                label: "内置模型",
                options: models.map((m) => ({
                  value: m.id,
                  label: m.configured ? m.name : `${m.name}（未配置）`,
                  disabled: !m.configured,
                })),
              },
              {
                label: "我的模型",
                options: userSlots.map((s) => {
                  const ephemeral = s.credentialMode === "EPHEMERAL";
                  const hasLocalKey = !ephemeral || !!getEphemeralUserModelKey(s.id);
                  return {
                    value: `user:${s.id}`,
                    label: ephemeral
                      ? hasLocalKey
                        ? `${s.name} · 仅本机`
                        : `${s.name} · 仅本机（未存 Key）`
                      : `${s.name} · 云端加密`,
                    disabled: !hasLocalKey,
                  };
                }),
              },
            ]}
          />
          <Tooltip title={isDark ? "切换浅色模式" : "切换深色模式"}>
            <Button
              size="small"
              type="text"
              aria-label="切换深浅色"
              icon={isDark ? <SunOutlined /> : <MoonOutlined />}
              onClick={() => setMode(isDark ? "light" : "dark")}
            />
          </Tooltip>
          <Tooltip title={authStatus === "authenticated" && authUser ? `账号：${authUser.username}` : "账号（登录/注册）"}>
            <Button
              size="small"
              type="text"
              aria-label="账号"
              icon={<UserOutlined />}
              onClick={() => setAuthOpen(true)}
            />
          </Tooltip>
          <Tooltip title="我的模型">
            <Button
              size="small"
              type="text"
              aria-label="我的模型"
              icon={<RobotOutlined />}
              onClick={() => setUserModelsOpen(true)}
            />
          </Tooltip>
          <Tooltip title="个人工作台">
            <Button
              size="small"
              type="text"
              aria-label="个人工作台"
              icon={<CloudOutlined />}
              onClick={() => setPersonalWorkspaceOpen(true)}
            />
          </Tooltip>
          <Tooltip title="模型设置">
            <Button
              size="small"
              type="text"
              aria-label="模型设置"
              icon={<SettingOutlined />}
              onClick={() => setSettingsOpen(true)}
            />
          </Tooltip>
          <Tooltip title="文件权限与工作区">
            <Button
              size="small"
              type="text"
              aria-label="文件权限设置"
              icon={<SafetyCertificateOutlined />}
              onClick={() => setPermissionSettingsOpen(true)}
            />
          </Tooltip>
          <Tooltip title="平台外部服务（AMAP / 数据库）">
            <Button
              size="small"
              type="text"
              aria-label="外部服务设置"
              icon={<GlobalOutlined />}
              onClick={() => setIntegrationOpen(true)}
            />
          </Tooltip>
          <Tooltip title="服务设置（后端接口地址）">
            <Button
              size="small"
              type="text"
              aria-label="服务设置"
              icon={<ApiOutlined />}
              onClick={() => setServerSettingsOpen(true)}
            />
          </Tooltip>
          <Tooltip title={rightCollapsed ? "展开任务面板" : "收起任务面板"}>
            <Button
              size="small"
              type="text"
              aria-label="切换任务面板"
              icon={<PicRightOutlined />}
              style={{ color: rightCollapsed ? token.colorPrimary : undefined }}
              onClick={onToggleRight}
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
      <IntegrationSettingsPanel
        open={integrationOpen}
        onClose={() => setIntegrationOpen(false)}
      />
      <PersonalWorkspacePanel
        open={personalWorkspaceOpen}
        onClose={() => setPersonalWorkspaceOpen(false)}
      />
      <FilePermissionSettings
        open={permissionSettingsOpen}
        onClose={() => setPermissionSettingsOpen(false)}
      />
      <AuthPanel
        open={authOpen}
        onClose={() => setAuthOpen(false)}
        onAuthChanged={loadModels}
      />
      <UserModelsPanel
        open={userModelsOpen}
        onClose={() => setUserModelsOpen(false)}
        onChanged={loadModels}
      />

      {/* 消息区 */}
      <div
        ref={scrollRef}
        onScroll={handleScroll}
        style={{ flex: 1, overflowY: "auto", padding: 16, position: "relative" }}
      >
        {!online && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 10 }}
            message="后端未连接"
            description="桌面版后端可能仍在启动；正在自动重试，也可以手动重试。"
            action={
              <Button size="small" onClick={refreshBackend}>
                重试
              </Button>
            }
          />
        )}

        {lastMessages.length === 0 && !streaming && (
          <div className="chat-empty">
            <div className="empty-mark">
              <VaticaMark size={44} />
            </div>
            <div className="chat-eyebrow">Vatica · 个人工作台</div>
            <Typography.Title level={4}>
              今天想完成什么？
            </Typography.Title>
            <Typography.Text type="secondary">
              一句话安排日程 / 生成周报 Word / 查天气——从常用任务开始：
            </Typography.Text>
            <div className="suggestion-grid">
              {SUGGESTIONS.map((s) => (
                <button
                  key={s.title}
                  type="button"
                  className="suggestion-card"
                  onClick={() => {
                    setInput(s.prompt);
                    requestAnimationFrame(() => inputRef.current?.focus());
                  }}
                >
                  <span className="suggestion-title">{s.title}</span>
                  <span className="suggestion-prompt">{s.prompt}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {lastMessages.map((m) => (
          <div key={m.id} className={`msg-row${m.role === "user" ? " user" : ""}`}>
            <div className={`msg-bubble${m.role === "user" ? " user" : " assistant"}`}>
              {m.role === "assistant" ? (
                <Markdown content={m.content} dark={isDark} />
              ) : (
                <span style={{ whiteSpace: "pre-wrap" }}>{m.content}</span>
              )}
              {m.id === streamingMessageId && !typing && (
                <span className="stream-caret" aria-hidden="true">
                  ▍
                </span>
              )}
              {m.content && !streaming && (
                <Flex justify="flex-end" style={{ marginTop: 2 }}>
                  <Button
                    type="text"
                    size="small"
                    aria-label="复制消息"
                    icon={<CopyOutlined />}
                    style={{ color: token.colorTextTertiary, fontSize: 11 }}
                    onClick={() => copyMessage(m.content)}
                  />
                </Flex>
              )}
              {m.note && (
                <Tag color="orange" style={{ marginTop: 4 }}>
                  {m.note}
                </Tag>
              )}
            </div>
          </div>
        ))}
        {reasoning && (
          <details className="reasoning-block" style={{ marginBottom: 12 }}>
            <summary style={{ cursor: "pointer", fontSize: 12, color: token.colorTextSecondary }}>
              思考过程（点击展开）
            </summary>
            <Typography.Paragraph
              type="secondary"
              style={{ fontSize: 12, margin: "8px 0 0", whiteSpace: "pre-wrap" }}
            >
              {reasoning}
            </Typography.Paragraph>
          </details>
        )}
        {toolActivity && (
          <div style={{ display: "flex", justifyContent: "flex-start", marginBottom: 12 }}>
            <Tag
              color={
                toolActivity.phase === "failed" ? "error" : toolActivity.phase === "end" ? "success" : "processing"
              }
              icon={
                toolActivity.phase === "failed" ? (
                  <ExclamationCircleOutlined />
                ) : toolActivity.phase === "end" ? (
                  <CheckCircleOutlined />
                ) : (
                  <LoadingOutlined />
                )
              }
              title={
                toolActivity.error ??
                ([
                  toolActivity.traceId ? `trace: ${toolActivity.traceId}` : "",
                  toolActivity.inputSummary ? `输入：${toolActivity.inputSummary}` : "",
                  toolActivity.outputSummary ? `输出：${toolActivity.outputSummary}` : "",
                ].filter(Boolean).join("\n") || undefined)
              }
            >
              {toolActivity.phase === "start" && `正在调用 ${toolActivity.tool}…`}
              {toolActivity.phase === "end" && `${toolActivity.tool} 已完成`}
              {toolActivity.phase === "failed" && `${toolActivity.tool} 执行失败`}
            </Tag>
          </div>
        )}
        {usage && (
          <div style={{ display: "flex", justifyContent: "flex-start", marginBottom: 12 }}>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              本次 {usage.totalTokens.toLocaleString()} tokens
              {usage.contextFillRatio != null ? ` · 上下文 ${usage.contextFillRatio}%` : ""}
              {usage.reasoningTokens > 0 ? ` · 思考 ${usage.reasoningTokens.toLocaleString()}` : ""}
            </Typography.Text>
          </div>
        )}
        {streaming && typing && (
          <div style={{ display: "flex", justifyContent: "flex-start", marginBottom: 12 }}>
            <Spin size="small" style={{ marginRight: 8 }} />
            <Typography.Text type="secondary">正在思考…</Typography.Text>
          </div>
        )}
        {showJumpToBottom && (
          <Button
            size="small"
            type="primary"
            ghost
            className="jump-to-bottom"
            onClick={() => {
              autoScrollRef.current = true;
              setShowJumpToBottom(false);
              scrollToBottom("smooth");
            }}
          >
            回到底部 ↓
          </Button>
        )}
      </div>

      {/* 输入区 */}
      <div style={{ padding: 12, borderTop: "1px solid var(--vatica-border)" }}>
        {/* 迭代 15 I15-4：快慢分离——深思开关仅对本轮聊天生效 */}
        <Flex justify="flex-end" align="center" gap={6} style={{ marginBottom: 6 }}>
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
            深思（慢思考）
          </Typography.Text>
          <Switch
            size="small"
            aria-label="深思开关"
            checked={deepThinking}
            onChange={setDeepThinking}
            checkedChildren="开"
            unCheckedChildren="关"
          />
        </Flex>
        <Flex gap={8} align="end">
          <Input.TextArea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="给 Vatica 发消息（Enter 发送，Shift+Enter 换行）"
            autoSize={{ minRows: 1, maxRows: 5 }}
            disabled={streaming || !online}
            onPressEnter={(e) => {
              // U1：中文输入法选词回车不发送
              if (e.nativeEvent.isComposing) return;
              if (!e.shiftKey) {
                e.preventDefault();
                void send();
              }
            }}
          />
          {streaming ? (
            <Button danger icon={<StopOutlined />} onClick={stop}>
              停止
            </Button>
          ) : (
            <Tooltip title={online ? undefined : "后端未连接"}>
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={() => void send()}
                disabled={!input.trim() || !online}
              >
                发送
              </Button>
            </Tooltip>
          )}
        </Flex>
      </div>

      <PermissionRequestModal
        request={permissionRequests[0] ?? null}
        deciding={permissionDeciding}
        remember={permissionRemember}
        onRememberChange={setPermissionRemember}
        onDecide={(approved) => void decidePermission(approved)}
      />
    </Flex>
  );
}
