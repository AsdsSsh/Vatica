import { Button, Empty, List, Typography } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import type { ChatSession } from "../types";

/**
 * 左栏：会话列表（迭代 6 I6-4 三栏布局）。
 * 会话仅存前端本地状态；持久化会话历史界面为 P1-2 可选项。
 */
interface Props {
  sessions: ChatSession[];
  activeId: string;
  onSelect: (id: string) => void;
  onNew: () => void;
}

export default function SessionList({ sessions, activeId, onSelect, onNew }: Props) {
  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div style={{ padding: 12 }}>
        <Button type="primary" block icon={<PlusOutlined />} onClick={onNew}>
          新对话
        </Button>
      </div>
      <div style={{ flex: 1, overflowY: "auto" }}>
        {sessions.length === 0 ? (
          <Empty description="暂无会话" style={{ marginTop: 48 }} />
        ) : (
          <List
            size="small"
            dataSource={sessions}
            renderItem={(s) => (
              <List.Item
                onClick={() => onSelect(s.id)}
                style={{
                  cursor: "pointer",
                  padding: "10px 16px",
                  background: s.id === activeId ? "rgba(22, 119, 255, 0.08)" : undefined,
                  borderInlineEnd: s.id === activeId ? "2px solid #1677ff" : undefined,
                }}
              >
                <Typography.Text
                  ellipsis={{ tooltip: s.title }}
                  strong={s.id === activeId}
                  style={{ fontSize: 13 }}
                >
                  {s.title}
                </Typography.Text>
              </List.Item>
            )}
          />
        )}
      </div>
    </div>
  );
}
