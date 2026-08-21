import { useEffect, useState } from "react";
import {
  App,
  Button,
  Checkbox,
  Descriptions,
  Divider,
  Empty,
  Flex,
  Input,
  List,
  Modal,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import {
  ArrowLeftOutlined,
  BookOutlined,
  CalendarOutlined,
  CheckCircleOutlined,
  CloseOutlined,
  DownloadOutlined,
  ExclamationCircleOutlined,
  FileTextOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import {
  approveMeetingPreparation,
  createMeetingPreparation,
  downloadWorkspaceFile,
  fetchMeetingCandidates,
  isAuthExpiredError,
  rejectMeetingPreparation,
  refreshMeetingPreparationDraft,
  type MeetingCandidate,
  type MeetingPreparationView,
} from "../api";
import { useTheme } from "../theme";
import Markdown from "./Markdown";

interface Props {
  open: boolean;
  onClose: () => void;
}

const KNOWLEDGE_STATUS: Record<string, { color: string; label: string }> = {
  READY: { color: "success", label: "已引用资料" },
  DEGRADED: { color: "processing", label: "仅日历与输入" },
  NOT_REQUESTED: { color: "default", label: "未检索资料" },
};

const PREPARATION_STATUS: Record<string, { color: string; label: string }> = {
  DRAFT: { color: "processing", label: "待批准" },
  APPLIED: { color: "success", label: "已写入" },
  REJECTED: { color: "default", label: "已拒绝" },
  FAILED: { color: "error", label: "写入失败" },
};

const ACTION_APPROVAL_STATUS: Record<string, { color: string; label: string }> = {
  PENDING: { color: "processing", label: "待批准" },
  APPROVED: { color: "success", label: "已批准" },
  REJECTED: { color: "default", label: "已拒绝" },
};

const ACTION_EXECUTION_STATUS: Record<string, { color: string; label: string }> = {
  NOT_STARTED: { color: "default", label: "未执行" },
  SUCCEEDED: { color: "success", label: "已完成" },
  FAILED: { color: "error", label: "执行失败" },
};

function todayValue(): string {
  return new Date().toISOString().slice(0, 10);
}

function nextWeekValue(): string {
  const date = new Date();
  date.setDate(date.getDate() + 7);
  return date.toISOString().slice(0, 10);
}

function displayTime(value: string): string {
  return value.replace("T", " ");
}

/**
 * 迭代 24B：会议准备的证据优先工作台。
 *
 * <p>先由用户选择具体日历事件，再展示无副作用草案。批准写入动作在 24C 接入，避免在
 * 未核对证据和待办差异时直接改变个人数据。</p>
 */
export default function MeetingPreparationPanel({ open, onClose }: Props) {
  const { message } = App.useApp();
  const { isDark } = useTheme();
  const [from, setFrom] = useState(todayValue);
  const [to, setTo] = useState(nextWeekValue);
  const [topic, setTopic] = useState("");
  const [goal, setGoal] = useState("");
  const [includeKnowledge, setIncludeKnowledge] = useState(false);
  const [candidates, setCandidates] = useState<MeetingCandidate[]>([]);
  const [selectedEventId, setSelectedEventId] = useState<number | null>(null);
  const [preparation, setPreparation] = useState<MeetingPreparationView | null>(null);
  const [rejectionReason, setRejectionReason] = useState("");
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    const initialFrom = todayValue();
    const initialTo = nextWeekValue();
    setFrom(initialFrom);
    setTo(initialTo);
    setTopic("");
    setGoal("");
    setIncludeKnowledge(false);
    setSelectedEventId(null);
    setPreparation(null);
    setRejectionReason("");
    void loadCandidates(initialFrom, initialTo, "");
    // 打开即按默认一周读取；后续由用户手动筛选，避免随输入频繁发请求。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  async function loadCandidates(nextFrom = from, nextTo = to, nextTopic = topic) {
    if (!nextFrom || !nextTo) return;
    setLoadingCandidates(true);
    try {
      const found = await fetchMeetingCandidates(nextFrom, nextTo, nextTopic);
      setCandidates(found);
      setSelectedEventId((current) => found.some((item) => item.eventId === current) ? current : null);
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setLoadingCandidates(false);
    }
  }

  async function createDraft() {
    if (selectedEventId == null || submitting) return;
    setSubmitting(true);
    try {
      const draft = await createMeetingPreparation({
        calendarEventId: selectedEventId,
        goal: goal.trim() || undefined,
        includeKnowledge,
      });
      setPreparation(draft);
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  async function updateDraft() {
    if (!preparation || submitting) return;
    setSubmitting(true);
    try {
      const draft = await refreshMeetingPreparationDraft(preparation.id, {
        goal: goal.trim() || undefined,
        includeKnowledge,
      });
      setPreparation(draft);
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  async function approveDraft() {
    if (!preparation || submitting) return;
    setSubmitting(true);
    try {
      const updated = await approveMeetingPreparation(preparation.id);
      setPreparation(updated);
      if (updated.status === "APPLIED") {
        message.success("会议准备已写入工作区和待办");
      } else if (updated.status === "FAILED") {
        message.error(updated.error || "会议准备写入失败，请查看错误信息后重试");
      }
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  async function rejectDraft() {
    if (!preparation || submitting) return;
    setSubmitting(true);
    try {
      setPreparation(await rejectMeetingPreparation(preparation.id, rejectionReason.trim() || undefined));
      message.info("已拒绝草案，未创建文档或待办");
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  async function downloadDocument() {
    if (!preparation?.documentPath) return;
    try {
      const blob = await downloadWorkspaceFile(preparation.documentPath);
      const link = document.createElement("a");
      const url = URL.createObjectURL(blob);
      link.href = url;
      link.download = preparation.documentPath.split("/").pop() || "meeting-preparation.md";
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    }
  }

  const draft = preparation?.draft;
  const status = draft ? KNOWLEDGE_STATUS[draft.knowledgeStatus] ?? KNOWLEDGE_STATUS.NOT_REQUESTED : null;
  const preparationStatus = preparation ? PREPARATION_STATUS[preparation.status] ?? PREPARATION_STATUS.DRAFT : null;
  const selected = candidates.find((candidate) => candidate.eventId === selectedEventId);

  return (
    <Modal
      title={<Space><CalendarOutlined />会议准备</Space>}
      open={open}
      onCancel={onClose}
      footer={null}
      width={920}
      destroyOnHidden
      styles={{ body: { maxHeight: "72vh", overflowY: "auto", paddingRight: 18 } }}
    >
      {!preparation ? (
        <Flex vertical gap={14}>
          <Flex gap={8} wrap align="end">
            <label>
              <Typography.Text type="secondary" style={{ display: "block", fontSize: 12 }}>开始日期</Typography.Text>
              <Input type="date" value={from} onChange={(event) => setFrom(event.target.value)} style={{ width: 150 }} />
            </label>
            <label>
              <Typography.Text type="secondary" style={{ display: "block", fontSize: 12 }}>结束日期</Typography.Text>
              <Input type="date" value={to} onChange={(event) => setTo(event.target.value)} style={{ width: 150 }} />
            </label>
            <label style={{ flex: 1, minWidth: 190 }}>
              <Typography.Text type="secondary" style={{ display: "block", fontSize: 12 }}>主题筛选</Typography.Text>
              <Input value={topic} placeholder="如：评审" onChange={(event) => setTopic(event.target.value)} />
            </label>
            <Tooltip title="查找会议">
              <Button icon={<SearchOutlined />} loading={loadingCandidates} onClick={() => void loadCandidates()}>
                查找
              </Button>
            </Tooltip>
          </Flex>

          <Table<MeetingCandidate>
            size="small"
            loading={loadingCandidates}
            rowKey="eventId"
            pagination={false}
            dataSource={candidates}
            locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前范围没有会议" /> }}
            rowSelection={{
              type: "radio",
              selectedRowKeys: selectedEventId == null ? [] : [selectedEventId],
              onChange: (keys) => setSelectedEventId(Number(keys[0])),
            }}
            columns={[
              { title: "会议", dataIndex: "title", ellipsis: true },
              { title: "开始", dataIndex: "start", width: 175, render: displayTime },
              { title: "结束", dataIndex: "end", width: 175, render: displayTime },
            ]}
          />

          <Divider style={{ margin: 0 }} />
          <Flex vertical gap={8}>
            <Typography.Text strong>准备目标</Typography.Text>
            <Input.TextArea
              value={goal}
              rows={3}
              maxLength={2000}
              showCount
              placeholder="例如：确认项目风险、决策项和会后负责人"
              onChange={(event) => setGoal(event.target.value)}
            />
            <Checkbox checked={includeKnowledge} onChange={(event) => setIncludeKnowledge(event.target.checked)}>
              检索授权资料
            </Checkbox>
          </Flex>
          <Flex justify="space-between" align="center">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {selected ? `${selected.title} · ${displayTime(selected.start)}` : "请选择一场会议"}
            </Typography.Text>
            <Button type="primary" icon={<FileTextOutlined />} loading={submitting} disabled={selectedEventId == null}
              onClick={() => void createDraft()}>
              生成草案
            </Button>
          </Flex>
        </Flex>
      ) : draft ? (
        <Flex vertical gap={14}>
          <Flex justify="space-between" align="center" wrap gap={8}>
            <Space wrap>
              {preparationStatus && <Tag color={preparationStatus.color}>{preparationStatus.label}</Tag>}
              {status && <Tag color={status.color}>{status.label}</Tag>}
            </Space>
            <Button size="small" icon={<ArrowLeftOutlined />} onClick={() => setPreparation(null)}>
              重新选择会议
            </Button>
          </Flex>

          <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
            <Descriptions.Item label="会议">{draft.meeting.title}</Descriptions.Item>
            <Descriptions.Item label="时间">{displayTime(draft.meeting.start)} 至 {displayTime(draft.meeting.end)}</Descriptions.Item>
            <Descriptions.Item label="准备目标" span={2}>{draft.goal || "未补充"}</Descriptions.Item>
          </Descriptions>

          {preparation.status === "DRAFT" && (
            <Flex vertical gap={8}>
              <Typography.Text strong>调整草案</Typography.Text>
              <Input.TextArea value={goal} rows={2} maxLength={2000} showCount
                onChange={(event) => setGoal(event.target.value)} />
              <Flex justify="space-between" align="center" wrap gap={8}>
                <Checkbox checked={includeKnowledge} onChange={(event) => setIncludeKnowledge(event.target.checked)}>
                  检索授权资料
                </Checkbox>
                <Button icon={<ReloadOutlined />} loading={submitting} onClick={() => void updateDraft()}>
                  更新草案
                </Button>
              </Flex>
            </Flex>
          )}

          <Divider style={{ margin: 0 }} />
          <Flex vertical gap={7}>
            <Typography.Text strong>来源</Typography.Text>
            <List size="small" dataSource={draft.evidence} renderItem={(item) => (
              <List.Item style={{ padding: "4px 0" }}>
                <Space align="start" size={6}>
                  <Tag color={item.type === "CALENDAR_EVENT" ? "blue" : "default"}>{item.label}</Tag>
                  <Typography.Text style={{ fontSize: 12 }}>{item.detail}</Typography.Text>
                </Space>
              </List.Item>
            )} />
            <Typography.Text type={draft.knowledgeStatus === "DEGRADED" ? "warning" : "secondary"} style={{ fontSize: 12 }}>
              <BookOutlined /> {draft.knowledgeMessage}
            </Typography.Text>
            {draft.citations.length > 0 && (
              <List size="small" dataSource={draft.citations} renderItem={(citation) => (
                <List.Item style={{ padding: "4px 0" }}>
                  <Typography.Text style={{ fontSize: 12 }}>
                    [{citation.citationId}] {citation.documentName} · {citation.sourcePath}
                  </Typography.Text>
                </List.Item>
              )} />
            )}
          </Flex>

          <Flex gap={18} wrap>
            <div style={{ flex: 1, minWidth: 260 }}>
              <Typography.Text strong>建议议程</Typography.Text>
              <List size="small" dataSource={draft.agendaSuggestions} renderItem={(item) => <List.Item style={{ padding: "4px 0" }}>{item}</List.Item>} />
            </div>
            <div style={{ flex: 1, minWidth: 260 }}>
              <Typography.Text strong>待确认事项</Typography.Text>
              <List size="small" dataSource={draft.openQuestions} renderItem={(item) => <List.Item style={{ padding: "4px 0" }}>{item}</List.Item>} />
            </div>
          </Flex>

          <div>
            <Typography.Text strong>待办差异</Typography.Text>
            <List size="small" dataSource={draft.todoDrafts} renderItem={(todo) => (
              <List.Item style={{ padding: "4px 0" }}>
                <Typography.Text style={{ fontSize: 12 }}>{todo.title}</Typography.Text>
                <Tag style={{ marginInlineEnd: 0 }}>{todo.due}</Tag>
              </List.Item>
            )} />
          </div>

          {preparation.actionPlan && (
            <div style={{ borderTop: "1px solid var(--vatica-border)", paddingTop: 10 }}>
              <Flex justify="space-between" align="center" wrap gap={8}>
                <Typography.Text strong><SafetyCertificateOutlined /> 执行动作</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  计划版本 {preparation.actionPlan.revision}
                </Typography.Text>
              </Flex>
              <List size="small" dataSource={preparation.actionPlan.actions} renderItem={(action) => {
                const approval = ACTION_APPROVAL_STATUS[action.approvalStatus] ?? {
                  color: "default", label: action.approvalStatus,
                };
                const execution = ACTION_EXECUTION_STATUS[action.executionStatus] ?? {
                  color: "default", label: action.executionStatus,
                };
                return (
                  <List.Item style={{ alignItems: "flex-start", padding: "9px 0" }}>
                    <Flex vertical gap={4} style={{ width: "100%" }}>
                      <Flex justify="space-between" align="center" wrap gap={6}>
                        <Typography.Text strong style={{ fontSize: 13 }}>{action.purpose}</Typography.Text>
                        <Space size={4} wrap>
                          <Tag color={action.risk === "HIGH" ? "error" : "gold"}>{action.risk === "HIGH" ? "高风险" : "需确认"}</Tag>
                          <Tag color={approval.color}>{approval.label}</Tag>
                          <Tag color={execution.color}>{execution.label}</Tag>
                        </Space>
                      </Flex>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {action.target} · {action.expectedChange}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                        输入：{action.inputSummary}
                      </Typography.Text>
                      <Flex gap={8} wrap align="center">
                        <Typography.Text code style={{ fontSize: 11 }}>{action.requiredPermission}</Typography.Text>
                        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                          幂等键：{action.idempotencyKey}
                        </Typography.Text>
                        {action.result && <Typography.Text type="success" style={{ fontSize: 11 }}>结果：{action.result}</Typography.Text>}
                      </Flex>
                    </Flex>
                  </List.Item>
                );
              }} />
            </div>
          )}

          <div style={{ borderTop: "1px solid var(--vatica-border)", paddingTop: 10 }}>
            <Typography.Text strong>准备文档预览</Typography.Text>
            <Markdown content={draft.documentPreview} dark={isDark} />
          </div>
          {preparation.status === "DRAFT" && (
            <Flex vertical gap={8} style={{ borderTop: "1px solid var(--vatica-border)", paddingTop: 10 }}>
              <Input
                value={rejectionReason}
                maxLength={1000}
                placeholder="拒绝原因（可选）"
                onChange={(event) => setRejectionReason(event.target.value)}
              />
              <Flex justify="flex-end" gap={8} wrap>
                <Button danger icon={<CloseOutlined />} loading={submitting} onClick={() => void rejectDraft()}>
                  拒绝草案
                </Button>
                <Button type="primary" icon={<CheckCircleOutlined />} loading={submitting} onClick={() => void approveDraft()}>
                  批准并创建文档和待办
                </Button>
              </Flex>
            </Flex>
          )}
          {preparation.status === "APPLIED" && (
            <Flex justify="space-between" align="center" wrap gap={8}>
              <Typography.Text type="success"><CheckCircleOutlined /> 已创建 {preparation.todoIds.length} 条待办</Typography.Text>
              {preparation.documentPath && (
                <Button icon={<DownloadOutlined />} onClick={() => void downloadDocument()}>下载准备文档</Button>
              )}
            </Flex>
          )}
          {preparation.status === "REJECTED" && (
            <Typography.Text type="secondary"><CloseOutlined /> 已拒绝：{preparation.rejectionReason || "未填写原因"}</Typography.Text>
          )}
          {preparation.status === "FAILED" && (
            <Flex justify="space-between" align="center" wrap gap={8}>
              <Typography.Text type="danger"><ExclamationCircleOutlined /> {preparation.error || "会议准备写入失败"}</Typography.Text>
              {preparation.documentPath && (
                <Button icon={<DownloadOutlined />} onClick={() => void downloadDocument()}>下载已写入文档</Button>
              )}
            </Flex>
          )}
        </Flex>
      ) : null}
    </Modal>
  );
}
