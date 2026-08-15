import { useMemo, useState } from "react";
import { App as AntApp, ConfigProvider, Layout, theme } from "antd";
import zhCN from "antd/locale/zh_CN";
import SessionList from "./components/SessionList";
import ChatPanel from "./components/ChatPanel";
import StepPanel from "./components/StepPanel";
import { createSession, type ChatMessage, type ChatSession } from "./types";
import "./App.css";

const { Sider, Content } = Layout;

/**
 * Vatica 主界面（迭代 6 I6-4）：三栏布局——
 * 左：会话列表 | 中：对话区（SSE 流式 Markdown） | 右：任务步骤面板。
 */
function App() {
  const [sessions, setSessions] = useState<ChatSession[]>(() => [createSession()]);
  const [activeId, setActiveId] = useState(() => sessions[0].id);
  const [streaming, setStreaming] = useState(false);

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

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{ algorithm: theme.defaultAlgorithm, token: { colorPrimary: "#1677ff" } }}
    >
      <AntApp>
        <Layout style={{ height: "100vh" }}>
          <Sider width={230} theme="light" style={{ borderRight: "1px solid #f0f0f0" }}>
            <div className="app-brand">Vatica · 个人 AI 助理</div>
            <SessionList
              sessions={sessions}
              activeId={active.id}
              disabled={streaming}
              onSelect={setActiveId}
              onNew={newSession}
            />
          </Sider>
          <Content style={{ display: "flex", flexDirection: "column", minWidth: 0 }}>
            <ChatPanel
              session={active}
              streaming={streaming}
              onStreamingChange={setStreaming}
              onAppendMessage={appendMessage}
              onUpdateMessage={updateMessage}
            />
          </Content>
          <Sider width={300} theme="light" style={{ borderLeft: "1px solid #f0f0f0" }}>
            <StepPanel />
          </Sider>
        </Layout>
      </AntApp>
    </ConfigProvider>
  );
}

export default App;
