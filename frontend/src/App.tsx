import { useMemo, useRef, useState } from "react";
import { useEffect } from "react";
import { App as AntApp, ConfigProvider, Layout, theme } from "antd";
import zhCN from "antd/locale/zh_CN";
import SessionList from "./components/SessionList";
import ChatPanel from "./components/ChatPanel";
import StepPanel from "./components/StepPanel";
import TitleBar from "./components/TitleBar";
import { createSession, type ChatMessage, type ChatSession } from "./types";
import { loadSessions, saveSessions } from "./sessions";
import { readUiPref, useTheme, writeUiPref } from "./theme";
import { setupDeepLinkFocus, startTodoReminders } from "./desktopShell";
import ObservabilityPage from "./ObservabilityPage";
import {
  deleteRemoteSession,
  fetchSessionDetail,
  fetchSessions,
  upsertSession,
} from "./api";
import "./App.css";

const { Sider, Content } = Layout;

/**
 * Vatica 主界面（迭代 6 I6-4 三栏布局；迭代 12 I12-1 视觉体系）——
 * 顶部自绘标题栏（U14，替代 Windows 默认边框）+ 三栏：
 * 左：会话列表 | 中：对话区（SSE 流式 Markdown） | 右：任务步骤面板。
 * 两侧栏可收起，状态持久化到 localStorage。
 */
function WorkspaceApp() {
  const { isDark } = useTheme();
  // 迭代 12 I12-5：会话从 localStorage 恢复，变更自动持久化
  const [sessions, setSessions] = useState<ChatSession[]>(loadSessions);
  const [activeId, setActiveId] = useState(() => sessions[0].id);
  const [streaming, setStreaming] = useState(false);
  const [leftCollapsed, setLeftCollapsed] = useState(() => readUiPref("vatica.leftCollapsed", false));
  const [rightCollapsed, setRightCollapsed] = useState(() => readUiPref("vatica.rightCollapsed", false));
  const [remoteLoaded, setRemoteLoaded] = useState(false);
  const sessionsRef = useRef(sessions);
  const hydrateGeneration = useRef(0);

  useEffect(() => {
    sessionsRef.current = sessions;
    saveSessions(sessions);
  }, [sessions]);

  // 迭代 35：桌面系统集成——待办到期通知轮询与深链接唤起（浏览器环境自动降级）
  useEffect(() => {
    startTodoReminders();
    void setupDeepLinkFocus();
  }, []);

  useEffect(() => {
    let disposed = false;
    async function hydrate(accountChanged = false) {
      const generation = ++hydrateGeneration.current;
      const local = loadSessions();
      if (accountChanged) {
        // token 已切换时先清掉旧账号内存态，网络慢/失败也不能短暂展示上一个账号的消息。
        setRemoteLoaded(false);
        setSessions(local);
        setActiveId(local[0].id);
      }
      try {
        const remote = await fetchSessions();
        if (disposed || generation !== hydrateGeneration.current) return;
        if (remote.length === 0) {
          setSessions(local);
          setActiveId(local[0].id);
          await Promise.all(local.map((s) => upsertSession(s.id, s.title)));
        } else {
          const details = await Promise.all(remote.map((s) => fetchSessionDetail(s.id)));
          if (disposed || generation !== hydrateGeneration.current) return;
          const hydrated: ChatSession[] = details.map((s) => ({
            id: s.id,
            title: s.title,
            createdAt: Date.parse(s.createdAt),
            messages: s.messages.map((m, index) => ({
              id: `${s.id}-${index}-${Date.parse(m.createdAt)}`,
              role: m.role === "USER" ? "user" : "assistant",
              content: m.content,
            })),
          }));
          if (hydrated.length > 0) {
            setSessions(hydrated);
            setActiveId(hydrated[0].id);
          }
        }
        if (!disposed && generation === hydrateGeneration.current) setRemoteLoaded(true);
      } catch {
        // 后端离线或未登录时继续使用当前账号自己的本机缓存。
      }
    }
    const onAuthChanged = () => void hydrate(true);
    window.addEventListener("vatica-auth-changed", onAuthChanged);
    void hydrate();
    return () => {
      disposed = true;
      window.removeEventListener("vatica-auth-changed", onAuthChanged);
    };
  }, []);

  useEffect(() => {
    if (!remoteLoaded) return;
    const timer = window.setTimeout(() => {
      void Promise.all(sessions.map((s) => upsertSession(s.id, s.title))).catch(() => undefined);
    }, 400);
    return () => window.clearTimeout(timer);
  }, [remoteLoaded, sessions.map((s) => `${s.id}:${s.title}`).join("|")]);

  // 迭代 18D：窄窗口优先保留聊天与任务创建区，侧栏仍可通过顶栏按钮手动展开。
  useEffect(() => {
    function collapseForNarrowWindow() {
      if (window.innerWidth < 1050) {
        setLeftCollapsed(true);
        setRightCollapsed(true);
      }
    }
    collapseForNarrowWindow();
    window.addEventListener("resize", collapseForNarrowWindow);
    return () => window.removeEventListener("resize", collapseForNarrowWindow);
  }, []);

  const active = useMemo(
    () => sessions.find((s) => s.id === activeId) ?? sessions[0],
    [sessions, activeId],
  );

  function newSession() {
    const s = createSession();
    setSessions((prev) => [s, ...prev]);
    setActiveId(s.id);
  }

  /** 追加消息；同时把首条用户消息提为会话标题。 */
  function appendMessage(msg: ChatMessage) {
    setSessions((prev) =>
      prev.map((s) => {
        if (s.id !== active.id) return s;
        const title =
          s.title === "新会话" && msg.role === "user"
            ? msg.content.slice(0, 20)
            : s.title;
        return { ...s, title, messages: [...s.messages, msg] };
      }),
    );
  }

  /** 函数式更新单条消息（SSE 流式追加）。 */
  function updateMessage(id: string, update: (m: ChatMessage) => ChatMessage) {
    setSessions((prev) =>
      prev.map((s) =>
        s.id !== active.id
          ? s
          : { ...s, messages: s.messages.map((m) => (m.id === id ? update(m) : m)) },
      ),
    );
  }

  /** 重命名会话（I12-5）。 */
  function renameSession(id: string, title: string) {
    const next = title.trim();
    if (!next) return;
    setSessions((prev) => prev.map((s) => (s.id === id ? { ...s, title: next.slice(0, 80) } : s)));
  }

  /** 删除会话：至少保底一个新会话（I12-5）。 */
  function deleteSession(id: string) {
    void deleteRemoteSession(id).catch(() => undefined);
    const remaining = sessions.filter((s) => s.id !== id);
    let next: ChatSession[];
    let nextActiveId: string;
    if (remaining.length === 0) {
      const fresh = createSession();
      next = [fresh];
      nextActiveId = fresh.id;
    } else {
      next = remaining;
      nextActiveId = activeId === id ? remaining[0].id : activeId;
    }
    setSessions(next);
    setActiveId(nextActiveId);
  }

  function toggleLeft() {
    setLeftCollapsed((v) => {
      writeUiPref("vatica.leftCollapsed", !v);
      return !v;
    });
  }

  function toggleRight() {
    setRightCollapsed((v) => {
      writeUiPref("vatica.rightCollapsed", !v);
      return !v;
    });
  }

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: {
          // skills.sh 风格：主按钮用灰白而非彩色
          colorPrimary: isDark ? "#EDEDED" : "#171717",
          colorInfo: "#F99C00",
          colorSuccess: "#00BB7F",
          colorWarning: "#F99C00",
          colorError: "#FB2C36",
          colorBgBase: isDark ? "#000000" : "#FFFFFF",
          colorTextBase: isDark ? "#EDEDED" : "#171717",
          borderRadius: 6,
          fontFamily:
            '"Geist", "Inter", "Segoe UI", "Microsoft YaHei UI", "PingFang SC", sans-serif',
        },
        components: {
          Layout: {
            bodyBg: "transparent",
            siderBg: "transparent",
          },
          Button: {
            primaryColor: isDark ? "#0A0A0A" : "#FFFFFF",
            primaryShadow: "none",
            defaultShadow: "none",
            defaultBg: isDark ? "#171717" : "#FFFFFF",
            defaultColor: isDark ? "#EDEDED" : "#171717",
            defaultBorderColor: isDark ? "#292929" : "#E6E6E6",
            defaultHoverBg: isDark ? "#1F1F1F" : "#F2F2F2",
            defaultHoverColor: isDark ? "#FFFFFF" : "#000000",
            defaultHoverBorderColor: isDark ? "#EDEDED" : "#171717",
            defaultActiveBg: isDark ? "#292929" : "#EBEBEB",
            defaultActiveColor: isDark ? "#FFFFFF" : "#000000",
            defaultActiveBorderColor: isDark ? "#EDEDED" : "#171717",
            textHoverBg: isDark ? "#1F1F1F" : "#F2F2F2",
          },
        },
      }}
    >
      <AntApp>
        <div className="app-root">
          <TitleBar working={streaming} />
          <Layout className="app-layout">
            <Sider
              width={230}
              collapsedWidth={0}
              collapsed={leftCollapsed}
              trigger={null}
              theme="light"
              className="app-sider app-sider-left"
              style={{
                borderRight: "1px solid var(--vatica-border)",
                backgroundColor: "var(--vatica-surface)",
              }}
            >
              <SessionList
                sessions={sessions}
                activeId={active.id}
                disabled={streaming}
                onSelect={setActiveId}
                onNew={newSession}
                onRename={renameSession}
                onDelete={deleteSession}
              />
            </Sider>
            <Content className="app-content">
              <ChatPanel
                session={active}
                streaming={streaming}
                leftCollapsed={leftCollapsed}
                rightCollapsed={rightCollapsed}
                onToggleLeft={toggleLeft}
                onToggleRight={toggleRight}
                onStreamingChange={setStreaming}
                onAppendMessage={appendMessage}
                onUpdateMessage={updateMessage}
              />
            </Content>
            <Sider
              width={300}
              collapsedWidth={0}
              collapsed={rightCollapsed}
              trigger={null}
              theme="light"
              className="app-sider app-sider-right"
              style={{
                borderLeft: "1px solid var(--vatica-border)",
                backgroundColor: "var(--vatica-surface)",
              }}
            >
              <StepPanel />
            </Sider>
          </Layout>
        </div>
      </AntApp>
    </ConfigProvider>
  );
}

/** 迭代 21C：观测工作台使用 Hash 路由，桌面壳与静态 Web 均无需服务端 rewrite。 */
function App() {
  const [hash, setHash] = useState(() => window.location.hash || "#/workspace");

  useEffect(() => {
    const onHashChange = () => setHash(window.location.hash || "#/workspace");
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  if (hash.startsWith("#/observability")) {
    return <ObservabilityPage key={hash} />;
  }
  return <WorkspaceApp />;
}

export default App;
