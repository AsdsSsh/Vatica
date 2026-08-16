import { useState } from "react";
import { App, Button, Empty, Flex, Input, List, Typography } from "antd";
import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import type { ChatSession } from "../types";

/**
 * 左栏：会话列表（迭代 6 I6-4 三栏布局；迭代 12 I12-1 视觉升级、I12-5 重命名/删除）。
 * 活动会话用品牌渐变条 + 靛蓝选中底色标识；重命名/删除由 App 持久化到 localStorage。
 */
interface Props {
  sessions: ChatSession[];
  activeId: string;
  /** 流式生成中：禁止切换/删除会话，避免消息串流到非活跃会话（迭代 10 I10-8）。 */
  disabled?: boolean;
  onSelect: (id: string) => void;
  onNew: () => void;
  onRename: (id: string, title: string) => void;
  onDelete: (id: string) => void;
}

export default function SessionList({
  sessions,
  activeId,
  disabled = false,
  onSelect,
  onNew,
  onRename,
  onDelete,
}: Props) {
  const { modal } = App.useApp();
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");

  function startEdit(session: ChatSession) {
    setEditingId(session.id);
    setDraft(session.title);
  }

  function commitEdit() {
    if (editingId) onRename(editingId, draft);
    setEditingId(null);
  }

  function confirmDelete(session: ChatSession) {
    modal.confirm({
      title: "删除会话",
      content: `删除「${session.title}」后，本机列表与消息将消失（至少保留一个新会话）。`,
      okText: "删除",
      okButtonProps: { danger: true },
      cancelText: "取消",
      onOk: () => onDelete(session.id),
    });
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div style={{ padding: "12px 12px 4px" }}>
        <Button type="primary" block icon={<PlusOutlined />} disabled={disabled} onClick={onNew}>
          新对话
        </Button>
      </div>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "10px 16px 6px",
        }}
      >
        <span className="sidebar-eyebrow">会话</span>
        <span className="sidebar-count">{sessions.length}</span>
      </div>
      <div style={{ flex: 1, overflowY: "auto", paddingBottom: 8 }}>
        {sessions.length === 0 ? (
          <Empty description="暂无会话" style={{ marginTop: 48 }} />
        ) : (
          <List
            size="small"
            split={false}
            dataSource={sessions}
            renderItem={(s) => (
              <List.Item
                className={`session-item${s.id === activeId ? " active" : ""}`}
                onClick={() => {
                  if (!disabled && editingId !== s.id) onSelect(s.id);
                }}
                style={{
                  cursor: disabled ? "not-allowed" : "pointer",
                  opacity: disabled && s.id !== activeId ? 0.55 : undefined,
                  padding: "9px 12px 9px 14px",
                  margin: "2px 8px",
                }}
              >
                <span className="session-active-bar" aria-hidden="true" />
                {editingId === s.id ? (
                  <Flex gap={4} align="center" style={{ width: "100%", minWidth: 0 }}>
                    <Input
                      size="small"
                      autoFocus
                      value={draft}
                      maxLength={80}
                      onChange={(e) => setDraft(e.target.value)}
                      onPressEnter={(e) => {
                        if (e.nativeEvent.isComposing) return;
                        commitEdit();
                      }}
                      onBlur={commitEdit}
                    />
                  </Flex>
                ) : (
                  <Flex justify="space-between" align="center" style={{ width: "100%", minWidth: 0 }}>
                    <Typography.Text
                      ellipsis={{ tooltip: s.title }}
                      strong={s.id === activeId}
                      style={{ fontSize: 13, minWidth: 0 }}
                    >
                      {s.title}
                    </Typography.Text>
                    <span
                      className="session-actions"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <Button
                        type="text"
                        size="small"
                        aria-label="重命名会话"
                        icon={<EditOutlined />}
                        disabled={disabled}
                        onClick={() => startEdit(s)}
                      />
                      <Button
                        type="text"
                        size="small"
                        danger
                        aria-label="删除会话"
                        icon={<DeleteOutlined />}
                        disabled={disabled}
                        onClick={() => confirmDelete(s)}
                      />
                    </span>
                  </Flex>
                )}
              </List.Item>
            )}
          />
        )}
      </div>
    </div>
  );
}
