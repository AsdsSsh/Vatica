import { useCallback, useEffect, useRef, useState } from "react";
import {
  App,
  Badge,
  Button,
  Empty,
  Flex,
  Input,
  List,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  ExclamationCircleOutlined,
  ExperimentOutlined,
  CloudOutlined,
  BranchesOutlined,
  FileSearchOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  StopOutlined,
  UndoOutlined,
} from "@ant-design/icons";
import {
  approvePermissionRequest,
  addTaskNote,
  createTask,
  denyPermissionRequest,
  fetchRecentTasks,
  fetchBenchmarkCases,
  fetchTaskDetail,
  fetchTaskTraces,
  isAuthExpiredError,
  subscribeTaskEvents,
  taskAction,
  type AgentTraceView,
  type BlackboardEntry,
  type BenchmarkCase,
  type FilePermissionRequest,
  type TaskDetail,
  type TaskSummary,
  type TaskEvent,
  type TaskStep,
} from "../api";
import { loadPermissionPolicy } from "../permissions";
import { useBackendStatus } from "../backendStatus";
import { useAuth } from "../auth";
import PermissionRequestModal from "./PermissionRequestModal";

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

const BLACKBOARD_LABEL: Record<BlackboardEntry["type"], string> = {
  result: "结果",
  note: "备注",
  "need-help": "求助",
  conflict: "冲突",
};

const BLACKBOARD_COLOR: Record<BlackboardEntry["type"], string> = {
  result: "success",
  note: "default",
  "need-help": "warning",
  conflict: "error",
};

function scoreColor(score: number | null): string {
  if (score == null) return "default";
  return score >= 70 ? "green" : "red";
}

/** 迭代 12 I12-8：任务相对时间（刚刚 / N 分钟前 / N 小时前 / N 天前）。 */
function relativeTime(createdAt: string): string {
  const time = Date.parse(createdAt);
  if (Number.isNaN(time)) return "";
  const diffMs = Date.now() - time;
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  return `${Math.floor(hours / 24)} 天前`;
}

/**
 * 右栏：任务面板（迭代 7 I7-1/2/3/4/6）——创建任务、任务列表、步骤实时打勾（SSE）、
 * 审批弹窗、终止按钮、准确率徽标 + 返工、文件产物列表（打开文件）。
 */
export default function StepPanel() {
  const { message } = App.useApp();
  const { online } = useBackendStatus();
  const { status: authStatus, user: authUser } = useAuth();
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [goalInput, setGoalInput] = useState("");
  const [benchmarkCases, setBenchmarkCases] = useState<BenchmarkCase[]>([]);
  const [benchmarkCaseId, setBenchmarkCaseId] = useState<string | null>(null);
  const [noteInput, setNoteInput] = useState("");
  const [arbitrationNote, setArbitrationNote] = useState("");
  const [noteBusy, setNoteBusy] = useState(false);
  const [busy, setBusy] = useState(false);
  const [approvalOpen, setApprovalOpen] = useState(false);
  const [permissionRequests, setPermissionRequests] = useState<FilePermissionRequest[]>([]);
  const [permissionDeciding, setPermissionDeciding] = useState(false);
  const [permissionRemember, setPermissionRemember] = useState(true);
  // 迭代 15 I15-1：步骤执行轨迹（agent_trace 查询弹窗）
  const [traceOpen, setTraceOpen] = useState(false);
  const [traceStepId, setTraceStepId] = useState<number | null>(null);
  const [traces, setTraces] = useState<AgentTraceView[]>([]);
  const [traceLoading, setTraceLoading] = useState(false);
  const prevStatus = useRef<string | null>(null);
  const selectedIdRef = useRef<string | null>(null);
  selectedIdRef.current = selectedId;
  /** 所有非终态任务的 SSE 订阅：切走任务后仍能接收权限请求（迭代 12 热修）。 */
  const taskSubscriptions = useRef<Map<string, () => void>>(new Map());

  const subscribeTask = useCallback((id: string) => {
    if (taskSubscriptions.current.has(id)) return;
    const close = subscribeTaskEvents(
      id,
      (e: TaskEvent) => {
        // 只更新当前选中的任务详情；其余任务只保持订阅通道存活
        if (selectedIdRef.current === id) setDetail(e);
        if (e.status === "DONE" || e.status === "FAILED" || e.status === "CANCELLED") {
          close();
          taskSubscriptions.current.delete(id);
        }
      },
      (permission) => {
        // 权限请求是全局弹窗，即使该任务未被选中也必须展示；
        // 迭代 13.5：并行任务可能同时请求权限，入队而不是互相覆盖
        setPermissionRemember(true);
        setPermissionRequests((prev) =>
          prev.some((p) => p.requestId === permission.requestId) ? prev : [...prev, permission],
        );
      },
      (entry) => {
        if (selectedIdRef.current !== id) return;
        setDetail((current) => {
          if (!current?.plan || typeof current.plan !== "object") return current;
          const board = current.plan.blackboard ?? [];
          const index = board.findIndex((item) => item.id === entry.id);
          const next = index >= 0
            ? board.map((item, i) => (i === index ? entry : item))
            : [...board, entry];
          return { ...current, plan: { ...current.plan, blackboard: next } };
        });
      },
    );
    taskSubscriptions.current.set(id, close);
  }, []);

  const syncSubscriptions = useCallback((list: TaskSummary[]) => {
    const terminal = new Set(["DONE", "FAILED", "CANCELLED"]);
    const activeIds = new Set(list.filter((t) => !terminal.has(t.status)).map((t) => t.id));
    for (const [id, close] of taskSubscriptions.current) {
      if (!activeIds.has(id)) {
        close();
        taskSubscriptions.current.delete(id);
      }
    }
    activeIds.forEach((id) => subscribeTask(id));
  }, [subscribeTask]);

  const refreshTasks = useCallback(async () => {
    try {
      const list = await fetchRecentTasks();
      setTasks(list);
      syncSubscriptions(list);
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error("任务列表加载失败（后端未启动？）");
    }
  }, [syncSubscriptions]);

  // 迭代 14.5：账号切换/退出时清空任务列表、详情与全部 SSE 订阅，再按新账号重新加载
  const authKey =
    authStatus === "authenticated" || authStatus === "local"
      ? `${authUser?.orgId ?? "-"}:${authUser?.userId ?? "-"}`
      : authStatus;
  useEffect(() => {
    taskSubscriptions.current.forEach((close) => close());
    taskSubscriptions.current.clear();
    setTasks([]);
    setDetail(null);
    setSelectedId(null);
    setApprovalOpen(false);
    setPermissionRequests([]);
    setBenchmarkCases([]);
    setBenchmarkCaseId(null);
    prevStatus.current = null;
    if (online && authStatus !== "loading" && authStatus !== "anonymous") {
      void refreshTasks();
      void fetchBenchmarkCases()
        .then(setBenchmarkCases)
        .catch((e: Error) => {
          if (!isAuthExpiredError(e)) message.warning("固定评测集加载失败");
        });
    }
  }, [authKey, online, authStatus, refreshTasks]);

  // 组件卸载时关闭全部任务订阅
  useEffect(() => {
    const subs = taskSubscriptions.current;
    return () => {
      subs.forEach((close) => close());
      subs.clear();
    };
  }, []);

  // 选中任务 → 拉详情（SSE 订阅由 syncSubscriptions 统一维护，切换任务不再断权限通道）
  useEffect(() => {
    if (!selectedId) return;
    let disposed = false;
    fetchTaskDetail(selectedId)
      .then((d) => {
        if (!disposed) setDetail(d);
      })
      .catch((e: Error) => {
        if (!disposed && !isAuthExpiredError(e)) message.error("任务详情加载失败");
      });
    return () => {
      disposed = true;
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
      const policy = loadPermissionPolicy();
      const created = await createTask(goal, policy, undefined, benchmarkCaseId ?? undefined);
      setGoalInput("");
      setBenchmarkCaseId(null);
      setSelectedId(created.id);
      setDetail(created);
      prevStatus.current = null; // 让审批弹窗对新任务触发一次
      refreshTasks();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function decidePermission(approved: boolean) {
    // 迭代 13.5：一次只处理队首请求，处理完自动轮到下一条
    const request = permissionRequests[0];
    if (!request) return;
    setPermissionDeciding(true);
    try {
      if (approved) {
        await approvePermissionRequest(request.requestId, permissionRemember);
      } else {
        await denyPermissionRequest(request.requestId);
      }
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error(`权限请求处理失败：${(e as Error).message}`);
    } finally {
      setPermissionDeciding(false);
      setPermissionRequests((prev) => prev.filter((p) => p.requestId !== request.requestId));
    }
  }

  /** 审批/返工/终止动作：异步发起，UI 由 SSE 事件驱动更新（不阻塞等待同步执行完成）。 */
  async function runAction(action: "approve" | "rework" | "cancel" | "resume") {
    if (!selectedId) return;
    setBusy(true);
    setApprovalOpen(false);
    try {
      const d = await taskAction(selectedId, action);
      setDetail(d); // 动作响应先落一次（SSE 事件随后持续更新）
      refreshTasks();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function writeHumanNote() {
    const content = noteInput.trim();
    if (!selectedId || !content || noteBusy) return;
    setNoteBusy(true);
    try {
      const updated = await addTaskNote(selectedId, content);
      setDetail(updated);
      setNoteInput("");
      message.success("已写入协作黑板");
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setNoteBusy(false);
    }
  }

  async function resolveArbitration() {
    const content = arbitrationNote.trim();
    if (!selectedId || !content || busy) return;
    setBusy(true);
    try {
      await addTaskNote(selectedId, content);
      const updated = await taskAction(selectedId, "approve");
      setDetail(updated);
      setArbitrationNote("");
      setApprovalOpen(false);
      refreshTasks();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  /** 迭代 15 I15-1：打开某一步骤的执行轨迹（Action/Observation 脱敏摘要）。 */
  async function openStepTraces(stepId: number) {
    if (!selectedId) return;
    setTraceStepId(stepId);
    setTraceOpen(true);
    setTraceLoading(true);
    setTraces([]);
    try {
      const all = await fetchTaskTraces(selectedId);
      setTraces(all.filter((t) => t.stepId === stepId));
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error(`读取执行轨迹失败：${(e as Error).message}`);
    } finally {
      setTraceLoading(false);
    }
  }

  const taskPlan = detail?.plan && typeof detail.plan === "object" ? detail.plan : undefined;
  const steps: TaskStep[] =
    detail?.plan && typeof detail.plan === "object" && Array.isArray(detail.plan.steps)
      ? detail.plan.steps
      : [];
  const blackboard = taskPlan?.blackboard ?? [];
  const openArbitrations = blackboard.filter(
    (entry) => entry.status === "OPEN" && (entry.type === "conflict" || entry.type === "need-help"),
  );
  const arbitrationPending = detail?.status === "PENDING_APPROVAL" && detail.pendingStepId < 0;
  const approvalStep = steps.find((s) => s.id === detail?.pendingStepId);
  // 执行中的步骤：RUNNING 且 currentStep 指向它（currentStep=下一个待执行步骤下标）
  const runningStepId =
    detail?.status === "RUNNING" && detail.currentStep != null && detail.currentStep >= 0
      ? steps[detail.currentStep]?.id
      : null;

  return (
    <Flex vertical style={{ height: "100%" }}>
      {/* 任务面板头 + 创建 */}
      <div style={{ padding: 12, borderBottom: "1px solid var(--vatica-border)" }}>
        <Flex justify="space-between" align="center" style={{ marginBottom: 8 }}>
          <Typography.Text strong>任务面板</Typography.Text>
          <Button size="small" aria-label="刷新任务列表" icon={<ReloadOutlined />} onClick={() => refreshTasks()} />
        </Flex>
        <div className="task-create-form">
          <Select
            size="small"
            className="task-create-case"
            allowClear
            value={benchmarkCaseId ?? undefined}
            placeholder="普通任务"
            aria-label="固定评测用例"
            style={{ width: "100%" }}
            options={benchmarkCases.map((item) => ({ value: item.id, label: item.title }))}
            onChange={(value: string | undefined) => {
              setBenchmarkCaseId(value ?? null);
              const selected = benchmarkCases.find((item) => item.id === value);
              if (selected) setGoalInput(selected.goal);
            }}
          />
          <Flex className="task-create-goal-row" gap={6}>
            <Input
              size="small"
              className="task-create-goal"
              placeholder="一句话任务，如：整理下周日程"
              value={goalInput}
              onChange={(e) => {
                const next = e.target.value;
                setGoalInput(next);
                const selected = benchmarkCases.find((item) => item.id === benchmarkCaseId);
                if (selected && next !== selected.goal) setBenchmarkCaseId(null);
              }}
              onPressEnter={(e) => {
                // 迭代 12 I12-3：中文输入法选词回车不创建任务
                if (e.nativeEvent.isComposing) return;
                void handleCreate();
              }}
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
        <Typography.Text type="secondary" style={{ display: "block", marginTop: 6, fontSize: 11 }}>
          <CloudOutlined /> 任务产物写入当前账号的个人云工作区
        </Typography.Text>
      </div>

      <div style={{ flex: 1, overflowY: "auto", padding: "0 12px 12px" }}>
        {/* 任务列表 */}
        <List
          size="small"
          split={false}
          dataSource={tasks}
          locale={{ emptyText: <Empty description="暂无任务，先创建一条试试" style={{ marginTop: 24 }} /> }}
          renderItem={(t) => (
            <List.Item
              className={`task-item${t.id === selectedId ? " active" : ""}`}
              onClick={() => {
                setSelectedId(t.id);
                setDetail(null);
                prevStatus.current = null;
                // 迭代 12 I12-8：切任务重置审批弹窗；权限弹窗保留（可能来自后台运行任务）
                setApprovalOpen(false);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  setSelectedId(t.id);
                  setDetail(null);
                  prevStatus.current = null;
                  setApprovalOpen(false);
                }
              }}
              role="button"
              tabIndex={0}
              aria-current={t.id === selectedId ? "true" : undefined}
              style={{ cursor: "pointer", padding: "8px 10px" }}
            >
              <Flex vertical gap={2} style={{ width: "100%" }}>
                <Space size={6}>
                  <Tag color={STATUS_COLOR[t.status]} style={{ marginInlineEnd: 0 }}>
                    {STATUS_LABEL[t.status] ?? t.status}
                  </Tag>
                  {t.benchmarkCaseId && (
                    <Tag icon={<ExperimentOutlined />} style={{ marginInlineEnd: 0, fontSize: 10 }}>评测</Tag>
                  )}
                  <Typography.Text
                    ellipsis={{ tooltip: t.goal }}
                    style={{ fontSize: 12, flex: 1 }}
                  >
                    {t.goal}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 11, flexShrink: 0 }}>
                    {relativeTime(t.createdAt)}
                  </Typography.Text>
                </Space>
              </Flex>
            </List.Item>
          )}
        />

        {/* 任务详情：步骤打勾 + 操作 + 准确率 */}
        {detail && (
          <div style={{ marginTop: 8, borderTop: "1px dashed var(--vatica-border-strong)", paddingTop: 8 }}>
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
              {detail.executionRuntime && <Tag>{detail.executionRuntime}</Tag>}
              {detail.benchmarkCaseId && (
                <Tag icon={<ExperimentOutlined />}>{detail.benchmarkCaseId}</Tag>
              )}
              {detail.executionAttempt > 1 && <Tag>执行尝试 {detail.executionAttempt}</Tag>}
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
                        <CheckCircleOutlined style={{ color: "var(--vatica-green)", marginTop: 3 }} />
                      ) : isPendingApproval ? (
                        <ExclamationCircleOutlined style={{ color: "var(--vatica-amber)", marginTop: 3 }} />
                      ) : isRunning ? (
                        <ClockCircleOutlined style={{ color: "var(--vatica-indigo)", marginTop: 3 }} />
                      ) : (
                        <ClockCircleOutlined style={{ color: "var(--vatica-text-tertiary)", marginTop: 3 }} />
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
                          <Typography.Paragraph
                            style={{ fontSize: 11, color: "var(--vatica-text-tertiary)", marginBottom: 0 }}
                            ellipsis={{ rows: 1, expandable: true, symbol: "展开" }}
                            copyable={{ text: s.result ?? "", tooltips: ["复制结果", "已复制"] }}
                          >
                            {s.result}
                          </Typography.Paragraph>
                        )}
                        {done && (
                          <Button
                            type="link"
                            size="small"
                            icon={<FileSearchOutlined />}
                            style={{ paddingInline: 0, fontSize: 11 }}
                            onClick={() => void openStepTraces(s.id)}
                          >
                            这步怎么做的
                          </Button>
                        )}
                      </div>
                    </Flex>
                  </List.Item>
                );
              }}
            />

            {/* 迭代 17B：紧凑协作轨道；不是独立卡片，保持任务面板的扫描密度。 */}
            <div style={{ borderTop: "1px solid var(--vatica-border)", paddingTop: 8, marginTop: 6 }}>
              <Flex justify="space-between" align="center" style={{ marginBottom: 4 }}>
                <Space size={5}>
                  <BranchesOutlined style={{ color: "var(--vatica-text-secondary)" }} />
                  <Typography.Text strong style={{ fontSize: 12 }}>协作黑板</Typography.Text>
                </Space>
                <Space size={4}>
                  {(taskPlan?.collaborationRevisionCount ?? 0) > 0 && (
                    <Tag style={{ marginInlineEnd: 0, fontSize: 10 }}>调整 {taskPlan?.collaborationRevisionCount}/1</Tag>
                  )}
                  {(taskPlan?.discoveryStepCount ?? 0) > 0 && (
                    <Tag style={{ marginInlineEnd: 0, fontSize: 10 }}>补步 {taskPlan?.discoveryStepCount}/2</Tag>
                  )}
                </Space>
              </Flex>
              {blackboard.length > 0 && (
                <List
                  size="small"
                  split={false}
                  dataSource={blackboard.slice(-8)}
                  renderItem={(entry) => (
                    <List.Item style={{ padding: "3px 0 3px 8px", borderLeft: "2px solid var(--vatica-border-strong)" }}>
                      <div style={{ minWidth: 0, width: "100%" }}>
                        <Space size={4} wrap>
                          <Tag color={BLACKBOARD_COLOR[entry.type]} style={{ marginInlineEnd: 0, fontSize: 10 }}>
                            {BLACKBOARD_LABEL[entry.type]}
                          </Tag>
                          <Typography.Text type="secondary" style={{ fontSize: 10 }}>
                            {entry.author.startsWith("HUMAN") ? "人工" : entry.agent}
                            {entry.stepId > 0 ? ` · 步骤 ${entry.stepId}` : ""}
                          </Typography.Text>
                          {entry.status !== "RECORDED" && (
                            <Tag style={{ marginInlineEnd: 0, fontSize: 10 }}>{entry.status}</Tag>
                          )}
                        </Space>
                        <Typography.Paragraph
                          style={{ fontSize: 11, margin: "2px 0 0", color: "var(--vatica-text-secondary)" }}
                          ellipsis={{ rows: 2, expandable: true, symbol: "展开" }}
                        >
                          {entry.content}
                        </Typography.Paragraph>
                      </div>
                    </List.Item>
                  )}
                />
              )}
              {!detail.status || !["DONE", "FAILED", "CANCELLED"].includes(detail.status) ? (
                <Flex gap={5} style={{ marginTop: 6 }}>
                  <Input
                    size="small"
                    value={noteInput}
                    maxLength={1000}
                    placeholder="给后续步骤补充信息"
                    onChange={(event) => setNoteInput(event.target.value)}
                    onPressEnter={(event) => {
                      if (!event.nativeEvent.isComposing) void writeHumanNote();
                    }}
                  />
                  <Button
                    size="small"
                    aria-label="写入协作备注"
                    title="写入协作备注"
                    icon={<SendOutlined />}
                    loading={noteBusy}
                    disabled={!noteInput.trim()}
                    onClick={() => void writeHumanNote()}
                  />
                </Flex>
              ) : null}
            </div>

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
              {detail.status === "FAILED" && detail.recoverable && (
                <Button
                  size="small"
                  type="primary"
                  icon={<ReloadOutlined />}
                  loading={busy}
                  onClick={() => runAction("resume")}
                >
                  继续执行
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

      </div>

      {/* 审批弹窗（I7-2）：PENDING=计划审批；PENDING_APPROVAL=敏感步骤审批 */}
      <Modal
        open={approvalOpen}
        title={
          arbitrationPending ? "协作冲突仲裁"
            : detail?.recoveryApprovalRequired ? "恢复执行确认"
            : detail?.status === "PENDING_APPROVAL" ? "敏感步骤审批" : "任务计划审批"
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
            disabled={arbitrationPending && !arbitrationNote.trim()}
            onClick={() => arbitrationPending ? void resolveArbitration() : runAction("approve")}
          >
            {arbitrationPending ? "确认裁决并继续" : "批准并继续"}
          </Button>,
        ]}
      >
        {arbitrationPending ? (
          <div>
            <List
              size="small"
              dataSource={openArbitrations}
              renderItem={(entry) => (
                <List.Item style={{ padding: "4px 0" }}>
                  <Typography.Text type={entry.type === "conflict" ? "danger" : "warning"} style={{ fontSize: 12 }}>
                    {entry.content}
                  </Typography.Text>
                </List.Item>
              )}
            />
            <Input.TextArea
              autoFocus
              rows={3}
              maxLength={1000}
              showCount
              value={arbitrationNote}
              placeholder="写下判断依据，系统会将冲突步骤串行化后继续"
              onChange={(event) => setArbitrationNote(event.target.value)}
            />
          </div>
        ) : detail?.recoveryApprovalRequired ? (
          <div>
            <Typography.Paragraph>
              服务重启时步骤 <b>{approvalStep?.id}</b> 的执行结果无法确认。
            </Typography.Paragraph>
            <Typography.Paragraph type="warning" style={{ background: "var(--vatica-warning-bg)", padding: 8, borderRadius: 8 }}>
              已完成步骤不会重跑。确认后只会从当前中断步骤继续，请先检查外部系统中是否已经产生副作用。
            </Typography.Paragraph>
          </div>
        ) : detail?.status === "PENDING_APPROVAL" ? (
          <div>
            <Typography.Paragraph>
              步骤 <b>{approvalStep?.id}</b> 涉及敏感操作，执行前需要你的确认：
            </Typography.Paragraph>
            <Typography.Paragraph type="warning" style={{ background: "var(--vatica-warning-bg)", padding: 8, borderRadius: 8 }}>
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

      {/* 迭代 15 I15-1：步骤执行轨迹（脱敏摘要级） */}
      <Modal
        title={`步骤 ${traceStepId ?? "-"} 执行轨迹`}
        open={traceOpen}
        onCancel={() => setTraceOpen(false)}
        footer={<Button onClick={() => setTraceOpen(false)}>关闭</Button>}
        width={860}
      >
        <Table<AgentTraceView>
          size="small"
          rowKey="id"
          loading={traceLoading}
          dataSource={traces}
          pagination={false}
          locale={{ emptyText: "该步骤还没有工具调用记录" }}
          columns={[
            {
              title: "Agent",
              dataIndex: "agentId",
              width: 130,
              render: (v: string | null, r: AgentTraceView) => (
                <Space direction="vertical" size={0}>
                  <Typography.Text className="vatica-mono" style={{ fontSize: 11 }}>{v ?? "-"}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 10 }}>{r.role ?? ""}</Typography.Text>
                </Space>
              ),
            },
            {
              title: "工具", dataIndex: "toolName", width: 130,
              render: (v: string) => <Typography.Text className="vatica-mono" style={{ fontSize: 12 }}>{v}</Typography.Text>,
            },
            {
              title: "状态", dataIndex: "status", width: 80,
              render: (v: string) => (
                <Tag color={v === "SUCCESS" ? "success" : "error"}>
                  {v === "SUCCESS" ? "成功" : "失败"}
                </Tag>
              ),
            },
            { title: "耗时", dataIndex: "durationMs", width: 90, render: (v: number) => `${v} ms` },
            {
              title: "输入（脱敏摘要）", dataIndex: "inputSummary",
              render: (v: string) => (
                <Typography.Paragraph style={{ fontSize: 11, marginBottom: 0 }} ellipsis={{ rows: 1, expandable: true, symbol: "展开" }}>
                  {v}
                </Typography.Paragraph>
              ),
            },
            {
              title: "输出（头尾摘要）", dataIndex: "outputSummary",
              render: (v: string, r) => (
                <Typography.Paragraph style={{ fontSize: 11, marginBottom: 0 }} ellipsis={{ rows: 1, expandable: true, symbol: "展开" }}>
                  {v || (r.error ? `失败：${r.error}` : "-")}
                </Typography.Paragraph>
              ),
            },
          ]}
        />
      </Modal>

      {/* 文件权限请求弹窗（迭代 11 引入；迭代 12 I12-6 统一为共享组件） */}
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
