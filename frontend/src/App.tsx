import { useMemo, useState } from "react";
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
import "./App.css";

const { Sider, Content } = Layout;

/**
 * Vatica 主界面（迭代 6 I6-4 三栏布局；迭代 12 I12-1 视觉体系）——
 * 顶部自绘标题栏（U14，替代 Windows 默认边框）+ 三栏：
 * 左：会话列表 | 中：对话区（SSE 流式 Markdown） | 右：任务步骤面板。
 * 两侧栏可收起，状态持久化到 localStorage。
 */
function App() {
  const { isDark } = useTheme();
  // 迭代 12 I12-5：会话从 localStorage 恢复，变更自动持久化
  const [sessions, setSessions] = useState<ChatSession[]>(loadSessions);
  const [activeId, setActiveId] = useState(() => sessions[0].id);
  const [streaming, setStreaming] = useState(false);
  const [leftCollapsed, setLeftCollapsed] = useState(() => readUiPref("vatica.leftCollapsed", false));
  const [rightCollapsed, setRightCollapsed] = useState(() => readUiPref("vatica.rightCollapsed", false));

  useEffect(() => {
    saveSessions(sessions);
  }, [sessions]);

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
              className="app-sider"
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
              className="app-sider"
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

export default App;
