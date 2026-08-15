import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  Collapse,
  Empty,
  Flex,
  Input,
  List,
  Modal,
  Space,
  Tag,
  Typography,
  message,
} from "antd";
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
  UndoOutlined,
} from "@ant-design/icons";
import {
  API_BASE,
  createTask,
  fetchFiles,
  fetchRecentTasks,
  fetchTaskDetail,
  subscribeTaskEvents,
  taskAction,
  type Artifact,
  type TaskDetail,
  type TaskSummary,
  type TaskEvent,
  type TaskStep,
} from "../api";

const STATUS_COLOR: Record<string, string> = {
  PENDING: "default",
  RUNNING: "processing",
  PENDING_APPROVAL: "warning",
  REVIEW: "processing",
  DONE: "success",
  RETRY: "warning",
  NEEDS_REVISION: "error",
  FAILED: "error",
  CANCELLED: "default",
};

const STATUS_LABEL: Record<string, string> = {
  PENDING: "待审批计划",
  RUNNING: "执行中",
  PENDING_APPROVAL: "待审批步骤",
  REVIEW: "评测中",
  DONE: "已完成",
  RETRY: "自动返工中",
  NEEDS_REVISION: "待人工返工",
  FAILED: "失败",
  CANCELLED: "已终止",
};

function scoreColor(score: number | null): string {
  if (score == null) return "default";
  return score >= 70 ? "green" : "red";
}

/**
 * 右栏：任务面板（迭代 7 I7-1/2/3/4/6）——创建任务、任务列表、步骤实时打勾（SSE）、
 * 审批弹窗、终止按钮、准确率徽标 + 返工、文件产物列表（打开文件）。
 */
export default function StepPanel() {
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [goalInput, setGoalInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [approvalOpen, setApprovalOpen] = useState(false);
  const [files, setFiles] = useState<Artifact[]>([]);
  const prevStatus = useRef<string | null>(null);

  const refreshTasks = useCallback(async () => {
    try {
      setTasks(await fetchRecentTasks());
    } catch {
      message.error("任务列表加载失败（后端未启动？）");
    }
  }, []);

  const refreshFiles = useCallback(async () => {
    try {
      setFiles(await fetchFiles());
    } catch {
      // 产物列表失败不打扰用户
    }
  }, []);

  useEffect(() => {
    refreshTasks();
    refreshFiles();
  }, [refreshTasks, refreshFiles]);

  // 选中任务 → 拉详情 + 订阅 SSE 进度事件（含快照回放）
  useEffect(() => {
    if (!selectedId) return;
    let disposed = false;
    fetchTaskDetail(selectedId)
      .then((d) => {
        if (!disposed) setDetail(d);
      })
      .catch(() => message.error("任务详情加载失败"));
    const close = subscribeTaskEvents(selectedId, (e: TaskEvent) => {
      if (!disposed) setDetail(e);
    });
    return () => {
      disposed = true;
      close();
    };
  }, [selectedId]);

  // 进入 PENDING（待审批计划）/ PENDING_APPROVAL（待审批步骤）→ 弹审批窗
  useEffect(() => {
    const status = detail?.status ?? null;
    if (
      status !== prevStatus.current &&
      (status === "PENDING" || status === "PENDING_APPROVAL")
    ) {
      setApprovalOpen(true);
    }
    prevStatus.current = status;
  }, [detail?.status]);

  async function handleCreate() {
    const goal = goalInput.trim();
    if (!goal || busy) return;
    setBusy(true);
    try {
      const created = await createTask(goal);
      setGoalInput("");
      setSelectedId(created.id);
      setDetail(created);
      prevStatus.current = null; // 让审批弹窗对新任务触发一次
      refreshTasks();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  /** 审批/返工/终止动作：异步发起，UI 由 SSE 事件驱动更新（不阻塞等待同步执行完成）。 */
  async function runAction(action: "approve" | "rework" | "cancel") {
    if (!selectedId) return;
    setBusy(true);
    setApprovalOpen(false);
    try {
      const d = await taskAction(selectedId, action);
      setDetail(d); // 动作响应先落一次（SSE 事件随后持续更新）
      refreshTasks();
      if (action === "cancel" || d.status === "DONE") refreshFiles();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function openFile(f: Artifact) {
    try {
      // Tauri 桌面：用系统默认程序打开本地文件（Word/Excel 等）
      const { openPath } = await import("@tauri-apps/plugin-opener");
      await openPath(f.absolutePath);
    } catch {
      // 浏览器开发模式降级：走后端下载接口
      window.open(`${API_BASE}/api/files/${encodeURIComponent(f.name)}`, "_blank");
    }
  }

  const steps: TaskStep[] =
    detail?.plan && typeof detail.plan === "object" && Array.isArray(detail.plan.steps)
      ? detail.plan.steps
      : [];
  const approvalStep = steps.find((s) => s.id === detail?.pendingStepId);
  // 执行中的步骤：RUNNING 且 currentStep 指向它（currentStep=下一个待执行步骤下标）
  const runningStepId =
    detail?.status === "RUNNING" && detail.currentStep != null && detail.currentStep >= 0
      ? steps[detail.currentStep]?.id
      : null;

  return (
    <Flex vertical style={{ height: "100%" }}>
      {/* 任务面板头 + 创建 */}
      <div style={{ padding: 12, borderBottom: "1px solid #f0f0f0" }}>
        <Flex justify="space-between" align="center" style={{ marginBottom: 8 }}>
          <Typography.Text strong>任务面板</Typography.Text>
          <Button size="small" icon={<ReloadOutlined />} onClick={() => { refreshTasks(); refreshFiles(); }} />
        </Flex>
        <Flex gap={6}>
          <Input
            size="small"
            placeholder="一句话任务，如：整理下周日程"
            value={goalInput}
            onChange={(e) => setGoalInput(e.target.value)}
            onPressEnter={handleCreate}
          />
          <Button
            size="small"
            type="primary"
            icon={<PlusOutlined />}
            loading={busy}
            onClick={handleCreate}
          >
            创建
          </Button>
        </Flex>
      </div>

      <div style={{ flex: 1, overflowY: "auto", padding: "0 12px 12px" }}>
        {/* 任务列表 */}
        <List
          size="small"
          dataSource={tasks}
          locale={{ emptyText: <Empty description="暂无任务，先创建一条试试" style={{ marginTop: 24 }} /> }}
          renderItem={(t) => (
            <List.Item
              onClick={() => {
                setSelectedId(t.id);
                setDetail(null);
                prevStatus.current = null;
              }}
              style={{
                cursor: "pointer",
                padding: "8px 4px",
                background: t.id === selectedId ? "rgba(22, 119, 255, 0.06)" : undefined,
              }}
            >
              <Flex vertical gap={2} style={{ width: "100%" }}>
                <Space size={6}>
                  <Tag color={STATUS_COLOR[t.status]} style={{ marginInlineEnd: 0 }}>
                    {STATUS_LABEL[t.status] ?? t.status}
                  </Tag>
                  <Typography.Text
                    ellipsis={{ tooltip: t.goal }}
                    style={{ fontSize: 12, flex: 1 }}
                  >
                    {t.goal}
                  </Typography.Text>
                </Space>
              </Flex>
            </List.Item>
          )}
        />

        {/* 任务详情：步骤打勾 + 操作 + 准确率 */}
        {detail && (
          <div style={{ marginTop: 8, borderTop: "1px dashed #f0f0f0", paddingTop: 8 }}>
            <Typography.Text strong style={{ fontSize: 13 }}>
              {detail.goal}
            </Typography.Text>
            <Flex gap={6} wrap style={{ margin: "6px 0" }}>
              <Tag color={STATUS_COLOR[detail.status]}>{STATUS_LABEL[detail.status] ?? detail.status}</Tag>
              {detail.score != null && (
                <Badge
                  count={`准确率 ${detail.score}`}
                  color={scoreColor(detail.score)}
                  title={`评测结论：${detail.verdict ?? "-"}`}
                />
              )}
              {detail.reworkCount > 0 && <Tag>返工 {detail.reworkCount} 次</Tag>}
            </Flex>
            {detail.error && (
              <Typography.Text type="danger" style={{ fontSize: 12 }}>
                {detail.error}
              </Typography.Text>
            )}

            {/* 步骤实时打勾（I7-1） */}
            <List
              size="small"
              dataSource={steps}
              style={{ marginTop: 4 }}
              renderItem={(s) => {
                const done = s.result != null && s.result !== "";
                const isPendingApproval = s.id === detail.pendingStepId;
                const isRunning = !done && !isPendingApproval && s.id === runningStepId;
                return (
                  <List.Item style={{ padding: "4px 0" }}>
                    <Flex align="start" gap={6} style={{ width: "100%" }}>
                      {done ? (
                        <CheckCircleOutlined style={{ color: "#52c41a", marginTop: 3 }} />
                      ) : isPendingApproval ? (
                        <ExclamationCircleOutlined style={{ color: "#faad14", marginTop: 3 }} />
                      ) : isRunning ? (
                        <ClockCircleOutlined style={{ color: "#1677ff", marginTop: 3 }} />
                      ) : (
                        <ClockCircleOutlined style={{ color: "#bbb", marginTop: 3 }} />
                      )}
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <Typography.Text style={{ fontSize: 12 }}>
                          {s.id}. {s.description}
                        </Typography.Text>
                        {s.needsApproval && (
                          <Tag color="warning" style={{ marginInlineStart: 4, fontSize: 10 }}>
                            需审批
                          </Tag>
                        )}
                        {done && (
                          <div
                            style={{
                              fontSize: 11,
                              color: "#999",
                              whiteSpace: "nowrap",
                              overflow: "hidden",
                              textOverflow: "ellipsis",
                            }}
                            title={s.result ?? ""}
                          >
                            {s.result}
                          </div>
                        )}
                      </div>
                    </Flex>
                  </List.Item>
                );
              }}
            />

            {/* 操作按钮（I7-2/4/6） */}
            <Flex gap={6} wrap style={{ marginTop: 8 }}>
              {(detail.status === "PENDING" || detail.status === "PENDING_APPROVAL") && (
                <Button
                  size="small"
                  type="primary"
                  loading={busy}
                  onClick={() => setApprovalOpen(true)}
                >
                  审批
                </Button>
              )}
              {(detail.status === "PENDING" ||
                detail.status === "RUNNING" ||
                detail.status === "PENDING_APPROVAL") && (
                <Button
                  size="small"
                  danger
                  icon={<StopOutlined />}
                  loading={busy}
                  onClick={() => runAction("cancel")}
                >
                  终止
                </Button>
              )}
              {(detail.status === "DONE" || detail.status === "NEEDS_REVISION") && (
                <Button
                  size="small"
                  icon={<UndoOutlined />}
                  loading={busy}
                  onClick={() => runAction("rework")}
                >
                  返工
                </Button>
              )}
            </Flex>
          </div>
        )}

        {/* 文件产物（I7-3） */}
        {files.length > 0 && (
          <Collapse
            size="small"
            ghost
            style={{ marginTop: 8 }}
            items={[
              {
                key: "artifacts",
                label: <Typography.Text strong style={{ fontSize: 13 }}>文件产物（{files.length}）</Typography.Text>,
                children: (
                  <List
                    size="small"
                    dataSource={files}
                    renderItem={(f) => (
                      <List.Item
                        style={{ padding: "4px 0" }}
                        actions={[
                          <Button
                            key="open"
                            size="small"
                            type="link"
                            icon={<FolderOpenOutlined />}
                            onClick={() => openFile(f)}
                          >
                            打开
                          </Button>,
                        ]}
                      >
                        <Typography.Text ellipsis={{ tooltip: f.absolutePath }} style={{ fontSize: 12 }}>
                          {f.name}
                        </Typography.Text>
                      </List.Item>
                    )}
                  />
                ),
              },
            ]}
          />
        )}
      </div>

      {/* 审批弹窗（I7-2）：PENDING=计划审批；PENDING_APPROVAL=敏感步骤审批 */}
      <Modal
        open={approvalOpen}
        title={
          detail?.status === "PENDING_APPROVAL" ? "敏感步骤审批" : "任务计划审批"
        }
        onCancel={() => setApprovalOpen(false)}
        footer={[
          <Button
            key="cancel"
            danger
            icon={<StopOutlined />}
            onClick={() => runAction("cancel")}
          >
            终止任务
          </Button>,
          <Button
            key="approve"
            type="primary"
            loading={busy}
            onClick={() => runAction("approve")}
          >
            批准并继续
          </Button>,
        ]}
      >
        {detail?.status === "PENDING_APPROVAL" ? (
          <div>
            <Typography.Paragraph>
              步骤 <b>{approvalStep?.id}</b> 涉及敏感操作，执行前需要你的确认：
            </Typography.Paragraph>
            <Typography.Paragraph type="warning" style={{ background: "#fffbe6", padding: 8, borderRadius: 6 }}>
              {approvalStep?.description}
            </Typography.Paragraph>
          </div>
        ) : (
          <div>
            <Typography.Paragraph>请确认任务拆解计划：</Typography.Paragraph>
            <List
              size="small"
              dataSource={steps}
              renderItem={(s) => (
                <List.Item style={{ padding: "4px 0" }}>
                  <Space size={6}>
                    <Typography.Text style={{ fontSize: 12 }}>
                      {s.id}. {s.description}
                    </Typography.Text>
                    {s.needsApproval && <Tag color="warning" style={{ fontSize: 10 }}>需审批</Tag>}
                  </Space>
                </List.Item>
              )}
            />
          </div>
        )}
      </Modal>
    </Flex>
  );
}
