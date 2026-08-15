import { useEffect, useState } from "react";
import { Button, Empty, List, Space, Tag, Typography } from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import { fetchRecentTasks, type TaskSummary } from "../api";

const STATUS_COLOR: Record<string, string> = {
  PENDING: "default",
  RUNNING: "processing",
  PENDING_APPROVAL: "warning",
  REVIEW: "processing",
  DONE: "success",
  RETRY: "warning",
  NEEDS_REVISION: "error",
  FAILED: "error",
};

/**
 * 右栏：任务步骤面板（迭代 6 I6-4 布局骨架：先接任务列表与状态）。
 * 步骤级实时打勾/审批弹窗在迭代 7 接入（I7-1/I7-2）。
 */
export default function StepPanel() {
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      setTasks(await fetchRecentTasks());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
  }, []);

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <Space style={{ padding: 12, justifyContent: "space-between", display: "flex" }}>
        <Typography.Text strong>任务面板</Typography.Text>
        <Button size="small" icon={<ReloadOutlined />} loading={loading} onClick={refresh}>
          刷新
        </Button>
      </Space>
      <div style={{ flex: 1, overflowY: "auto", padding: "0 12px 12px" }}>
        {error && <Typography.Text type="danger">{error}</Typography.Text>}
        {!error && tasks.length === 0 && (
          <Empty description="暂无任务" style={{ marginTop: 48 }} />
        )}
        <List
          size="small"
          dataSource={tasks}
          renderItem={(t) => (
            <List.Item style={{ display: "block", padding: "10px 4px" }}>
              <Space direction="vertical" size={4} style={{ width: "100%" }}>
                <Tag color={STATUS_COLOR[t.status] ?? "default"}>{t.status}</Tag>
                <Typography.Text
                  ellipsis={{ tooltip: t.goal }}
                  style={{ fontSize: 13 }}
                >
                  {t.goal}
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {new Date(t.createdAt).toLocaleString()}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          步骤实时进度与审批操作将在迭代 7 接入。
        </Typography.Text>
      </div>
    </div>
  );
}
