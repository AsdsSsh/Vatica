import { useState } from "react";
import {
  Alert,
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
  Tag,
  Tabs,
  Typography,
} from "antd";
import { CheckCircleOutlined, ClockCircleOutlined, EditOutlined, FileTextOutlined, ReloadOutlined, SaveOutlined } from "@ant-design/icons";
import {
  collectWeeklyReportFacts,
  createWeeklyReportDraft,
  isAuthExpiredError,
  updateWeeklyReportDraft,
  type WeeklyReportDraftView,
  type WeeklyReportFactsView,
  type WeeklyReportSourceView,
} from "../api";
import Markdown from "./Markdown";
import { useTheme } from "../theme";

interface Props {
  open: boolean;
  onClose: () => void;
}

const SOURCE_LABEL: Record<string, string> = {
  CALENDAR: "日程",
  TODO: "待办",
  KNOWLEDGE: "资料",
  USER_INPUT: "用户补充",
};

const SOURCE_STATUS: Record<string, { color: string; label: string }> = {
  READY: { color: "success", label: "已读取" },
  EMPTY: { color: "default", label: "为空" },
  DEGRADED: { color: "warning", label: "已降级" },
  NOT_SELECTED: { color: "default", label: "未选择" },
};

function dateValue(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

function sourceStatus(source: WeeklyReportSourceView) {
  return SOURCE_STATUS[source.status] ?? { color: "default", label: source.status };
}

/** 迭代 26A：只读事实工作台，先让用户确认数据口径，再进入 26B 草案。 */
export default function WeeklyReportPanel({ open, onClose }: Props) {
  const { message } = App.useApp();
  const { isDark } = useTheme();
  const [from, setFrom] = useState(() => dateValue(-6));
  const [to, setTo] = useState(() => dateValue(0));
  const [includeCalendar, setIncludeCalendar] = useState(true);
  const [includeTodos, setIncludeTodos] = useState(true);
  const [includeKnowledge, setIncludeKnowledge] = useState(false);
  const [userNotes, setUserNotes] = useState("");
  const [facts, setFacts] = useState<WeeklyReportFactsView | null>(null);
  const [draft, setDraft] = useState<WeeklyReportDraftView | null>(null);
  const [title, setTitle] = useState("");
  const [focus, setFocus] = useState("");
  const [risks, setRisks] = useState("");
  const [nextPlan, setNextPlan] = useState("");
  const [wordRequested, setWordRequested] = useState(true);
  const [excelRequested, setExcelRequested] = useState(true);
  const [loading, setLoading] = useState(false);

  function reset() {
    setFrom(dateValue(-6));
    setTo(dateValue(0));
    setIncludeCalendar(true);
    setIncludeTodos(true);
    setIncludeKnowledge(false);
    setUserNotes("");
    setFacts(null);
    setDraft(null);
    setTitle("");
    setFocus("");
    setRisks("");
    setNextPlan("");
    setWordRequested(true);
    setExcelRequested(true);
  }

  async function collect() {
    if (!from || !to) return;
    setLoading(true);
    try {
      const value = await collectWeeklyReportFacts({
        from,
        to,
        reportType: "WEEKLY",
        includeCalendar,
        includeTodos,
        includeKnowledge,
        userNotes,
      });
      setFacts(value);
      setDraft(null);
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  }

  function applyDraft(value: WeeklyReportDraftView) {
    setDraft(value);
    setFacts(value.facts);
    setTitle(value.title);
    setFocus(value.focus);
    setRisks(value.risks);
    setNextPlan(value.nextPlan);
    setWordRequested(value.wordRequested);
    setExcelRequested(value.excelRequested);
  }

  async function createDraft() {
    setLoading(true);
    try {
      applyDraft(await createWeeklyReportDraft({
        from,
        to,
        reportType: "WEEKLY",
        includeCalendar,
        includeTodos,
        includeKnowledge,
        userNotes,
        title,
        focus,
        risks,
        nextPlan,
        wordRequested,
        excelRequested,
      }));
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function saveDraft() {
    if (!draft) return;
    setLoading(true);
    try {
      applyDraft(await updateWeeklyReportDraft(draft.id, {
        title,
        focus,
        risks,
        nextPlan,
        wordRequested,
        excelRequested,
      }));
      message.success("周报草案已更新");
    } catch (error) {
      if (!isAuthExpiredError(error)) message.error((error as Error).message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal
      title={<Space><FileTextOutlined /> 周报事实</Space>}
      open={open}
      onCancel={onClose}
      width={880}
      footer={null}
      destroyOnHidden
      afterOpenChange={(visible) => { if (visible) reset(); }}
    >
      <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
        先核对这一周的事实和统计口径。此处只读取当前账号的数据，不生成文件、不新增待办、不发送邮件。
      </Typography.Paragraph>
      <Flex gap={8} wrap="wrap" align="end">
        <label style={{ display: "grid", gap: 4, fontSize: 12 }}>
          开始日期
          <Input type="date" value={from} onChange={(event) => setFrom(event.target.value)} style={{ width: 150 }} />
        </label>
        <label style={{ display: "grid", gap: 4, fontSize: 12 }}>
          结束日期
          <Input type="date" value={to} onChange={(event) => setTo(event.target.value)} style={{ width: 150 }} />
        </label>
        <Checkbox checked={includeCalendar} onChange={(event) => setIncludeCalendar(event.target.checked)}>日程</Checkbox>
        <Checkbox checked={includeTodos} onChange={(event) => setIncludeTodos(event.target.checked)}>待办</Checkbox>
        <Checkbox checked={includeKnowledge} onChange={(event) => setIncludeKnowledge(event.target.checked)}>资料就绪度</Checkbox>
        <Button type="primary" icon={<ReloadOutlined />} loading={loading} onClick={() => void collect()}>读取事实</Button>
      </Flex>
      <Input.TextArea
        value={userNotes}
        onChange={(event) => setUserNotes(event.target.value)}
        placeholder="可选：补充本周重点、风险或下周关注事项（不会被当作外部事实）"
        maxLength={2000}
        showCount
        autoSize={{ minRows: 2, maxRows: 4 }}
        style={{ marginTop: 10 }}
      />

      {facts && (
        <div style={{ marginTop: 16 }}>
          <Flex justify="space-between" align="center" wrap="wrap" gap={8}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {facts.from} ～ {facts.to} · {new Date(facts.collectedAt).toLocaleString()}
            </Typography.Text>
            <Space size={4} wrap>
              {facts.sources.map((source) => {
                const status = sourceStatus(source);
                return <Tag key={source.source} color={status.color}>{SOURCE_LABEL[source.source] ?? source.source} · {status.label} {source.recordCount}</Tag>;
              })}
            </Space>
          </Flex>
          <Descriptions size="small" bordered column={{ xs: 1, sm: 3 }} style={{ marginTop: 10 }}>
            <Descriptions.Item label="会议">{facts.statistics.meetingCount}</Descriptions.Item>
            <Descriptions.Item label="完成待办">{facts.statistics.completedTodoCount}</Descriptions.Item>
            <Descriptions.Item label="未完成待办">{facts.statistics.pendingTodoCount}</Descriptions.Item>
            <Descriptions.Item label="逾期待办">{facts.statistics.overdueTodoCount}</Descriptions.Item>
            <Descriptions.Item label="待办总数">{facts.statistics.todoCount}</Descriptions.Item>
            <Descriptions.Item label="阶段">事实快照</Descriptions.Item>
          </Descriptions>
          {facts.warnings.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginTop: 10 }}
              message="本次统计的边界"
              description={<List size="small" split={false} dataSource={facts.warnings} renderItem={(item) => <List.Item style={{ padding: "2px 0" }}>{item}</List.Item>} />}
            />
          )}
          <Divider plain>日程事实</Divider>
          {facts.calendar.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="范围内没有日程" /> : (
            <List
              size="small"
              dataSource={facts.calendar}
              renderItem={(item) => <List.Item>
                <Space><ClockCircleOutlined /><Typography.Text>{item.title}</Typography.Text><Typography.Text type="secondary">{item.start} ～ {item.end}</Typography.Text>{item.recurring && <Tag>重复</Tag>}</Space>
              </List.Item>}
            />
          )}
          <Divider plain>待办事实</Divider>
          {facts.todos.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="范围内没有带截止日期的待办" /> : (
            <List
              size="small"
              dataSource={facts.todos}
              renderItem={(item) => <List.Item>
                <Space><CheckCircleOutlined style={{ color: item.done ? "var(--vatica-green)" : "var(--vatica-text-tertiary)" }} /><Typography.Text delete={item.done}>{item.title}</Typography.Text><Typography.Text type="secondary">截止 {item.due}</Typography.Text></Space>
              </List.Item>}
            />
          )}
          <Divider plain>周报草案</Divider>
          <Flex vertical gap={10}>
            <Input value={title} onChange={(event) => setTitle(event.target.value)} maxLength={240} placeholder="周报标题（留空使用日期范围）" />
            <Input.TextArea value={focus} onChange={(event) => setFocus(event.target.value)} maxLength={2000} autoSize={{ minRows: 2, maxRows: 5 }} placeholder="本周重点（留空使用确定性统计摘要）" />
            <Input.TextArea value={risks} onChange={(event) => setRisks(event.target.value)} maxLength={2000} autoSize={{ minRows: 2, maxRows: 5 }} placeholder="风险与阻塞（留空按逾期待办生成）" />
            <Input.TextArea value={nextPlan} onChange={(event) => setNextPlan(event.target.value)} maxLength={2000} autoSize={{ minRows: 2, maxRows: 5 }} placeholder="下周计划" />
            <Flex justify="space-between" align="center" wrap="wrap" gap={8}>
              <Space wrap>
                <Checkbox checked={wordRequested} onChange={(event) => setWordRequested(event.target.checked)}>Word 模板</Checkbox>
                <Checkbox checked={excelRequested} onChange={(event) => setExcelRequested(event.target.checked)}>Excel 模板</Checkbox>
              </Space>
              {draft ? (
                <Button type="primary" icon={<SaveOutlined />} loading={loading} onClick={() => void saveDraft()}>保存草案</Button>
              ) : (
                <Button type="primary" icon={<EditOutlined />} loading={loading} onClick={() => void createDraft()}>创建草案</Button>
              )}
            </Flex>
          </Flex>
          {draft && (
            <>
              <Flex justify="space-between" align="center" style={{ marginTop: 14 }}>
                <Typography.Text strong>交付物预览</Typography.Text>
                <Tag color="processing">事实快照已冻结</Tag>
              </Flex>
              <Tabs
                size="small"
                items={[
                  ...(draft.wordPreview ? [{
                    key: "word",
                    label: "Word",
                    children: <div style={{ maxHeight: 330, overflow: "auto", paddingRight: 8 }}><Markdown content={draft.wordPreview} dark={isDark} /></div>,
                  }] : []),
                  ...(draft.excelPreview ? [{
                    key: "excel",
                    label: "Excel",
                    children: <pre className="vatica-mono" style={{ margin: 0, maxHeight: 330, overflow: "auto", padding: 12, background: "var(--vatica-surface)", border: "1px solid var(--vatica-border)", borderRadius: 6, whiteSpace: "pre-wrap" }}>{draft.excelPreview}</pre>,
                  }] : []),
                  {
                    key: "artifacts",
                    label: `产物 ${draft.artifacts.length}`,
                    children: <List
                      size="small"
                      dataSource={draft.artifacts}
                      renderItem={(item) => <List.Item>
                        <Flex justify="space-between" align="center" style={{ width: "100%" }} gap={8}>
                          <div><Typography.Text>{item.name}</Typography.Text><br /><Typography.Text type="secondary" style={{ fontSize: 11 }}>{item.summary}</Typography.Text></div>
                          <Tag>{item.status === "PREVIEW" ? "预览" : item.status === "CANCELLED" ? "未选择" : item.status}</Tag>
                        </Flex>
                      </List.Item>}
                    />,
                  },
                ]}
              />
              <Alert type="info" showIcon message="26B 只保存草案和模板预览；Word/Excel 文件将在 26C 明确批准后写入工作区。" />
            </>
          )}
        </div>
      )}
    </Modal>
  );
}
