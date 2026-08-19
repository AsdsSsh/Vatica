import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  ConfigProvider,
  Empty,
  Layout,
  Progress,
  Row,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  theme,
} from "antd";
import zhCN from "antd/locale/zh_CN";
import {
  ArrowLeftOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import {
  fetchObservabilityOverview,
  fetchObservabilityRuns,
  fetchObservabilityTrace,
  subscribeTaskEvents,
  type ObservabilityOverview,
  type ObservabilityRun,
  type ObservabilitySpan,
} from "./api";
import { useTheme } from "./theme";
import "./App.css";

const { Header, Content } = Layout;

function formatMs(value: number): string {
  if (value < 1000) return value + " ms";
  if (value < 60000) return (value / 1000).toFixed(1) + " s";
  return (value / 60000).toFixed(1) + " min";
}

function formatTime(value: string | null): string {
  if (!value) return "进行中";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", {
    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
}

function formatWindow(start: string | null, end: string | null): string {
  if (!start && !end) return "暂无数据";
  return formatTime(start) + " - " + formatTime(end);
}

function statusTag(status: string) {
  const color = status === "SUCCESS" || status === "DONE" ? "success"
    : status === "FAILED" ? "error" : status === "CANCELLED" ? "default" : "processing";
  const label = status === "SUCCESS" ? "成功" : status === "FAILED" ? "失败"
    : status === "CANCELLED" ? "已取消" : status === "OPEN" ? "进行中" : status;
  return <Tag color={color}>{label}</Tag>;
}

function spanColor(status: string): string {
  return status === "FAILED" ? "var(--vatica-red)" : status === "SUCCESS"
    ? "var(--vatica-green)" : "var(--vatica-amber)";
}

export default function ObservabilityPage() {
  const { isDark } = useTheme();
  const [overview, setOverview] = useState<ObservabilityOverview | null>(null);
  const [runs, setRuns] = useState<ObservabilityRun[]>([]);
  const [spans, setSpans] = useState<ObservabilitySpan[]>([]);
  const [selectedSpan, setSelectedSpan] = useState<ObservabilitySpan | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const traceId = useMemo(() => {
    const prefix = "#/observability/traces/";
    return window.location.hash.startsWith(prefix)
      ? decodeURIComponent(window.location.hash.slice(prefix.length)) : null;
  }, []);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      if (traceId) {
        const nextSpans = await fetchObservabilityTrace(traceId);
        setSpans(nextSpans);
        setSelectedSpan(nextSpans[0] ?? null);
      } else {
        const [nextOverview, nextRuns] = await Promise.all([
          fetchObservabilityOverview(20), fetchObservabilityRuns(50),
        ]);
        setOverview(nextOverview);
        setRuns(nextRuns);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "观测数据加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [traceId]);

  useEffect(() => {
    const taskId = spans.find((span) => span.taskId)?.taskId;
    if (!traceId || !taskId) return;
    return subscribeTaskEvents(taskId, () => { void load(); });
  }, [traceId, spans.find((span) => span.taskId)?.taskId]);

  const columns = [
    { title: "状态", dataIndex: "status", width: 88, render: (value: string) => statusTag(value) },
    {
      title: "Trace", dataIndex: "traceId", width: 250,
      render: (value: string, row: ObservabilityRun) => (
        <button className="observability-link"
          onClick={() => { window.location.hash = "#/observability/traces/" + encodeURIComponent(value); }}>
          <span className="observability-trace-id">{value.slice(0, 12)}...</span>
          <span className="observability-subtle">{row.taskId ? "任务 " + row.taskId.slice(0, 8) : "无任务"}</span>
        </button>
      ),
    },
    { title: "运行时", dataIndex: "runtime", width: 120, render: (value: string | null) => value ?? "未设置" },
    { title: "耗时", dataIndex: "durationMs", width: 100, render: (value: number) => formatMs(value) },
    {
      title: "Span", dataIndex: "spanCount", width: 74,
      render: (value: number, row: ObservabilityRun) => (
        <span className={row.failedSpanCount ? "observability-failed-count" : ""}>
          {value}{row.failedSpanCount ? " / " + row.failedSpanCount + " 失败" : ""}
        </span>
      ),
    },
    { title: "Tokens", dataIndex: "totalTokens", width: 90, render: (value: number | null) => value == null ? "-" : value.toLocaleString() },
    { title: "开始时间", dataIndex: "startedAt", width: 160, render: (value: string | null) => formatTime(value) },
  ];

  return (
    <ConfigProvider locale={zhCN} theme={{
      algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
      token: { colorPrimary: "#f99c00", borderRadius: 7, fontFamily: "var(--vatica-sans)" },
    }}>
      <AntApp>
        <Layout className="observability-page">
          <Header className="observability-header">
            <div className="observability-brand">
              <span className="observability-brand-mark">V</span><span>Agent 可观测性</span>
              <span className="observability-eyebrow">诊断工作台</span>
            </div>
            <Space>
              <Tooltip title="返回工作台"><Button icon={<ArrowLeftOutlined />} onClick={() => { window.location.hash = "#/workspace"; }}>工作台</Button></Tooltip>
              <Tooltip title="刷新当前视图"><Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading} /></Tooltip>
            </Space>
          </Header>
          <Content className="observability-content">
            {error && <Alert type="error" showIcon title={error} className="observability-alert" />}
            {loading ? <div className="observability-loading"><Spin size="large" /></div>
              : traceId ? <TraceView spans={spans} selectedSpan={selectedSpan} onSelect={setSelectedSpan} />
                : <OverviewView overview={overview} runs={runs} columns={columns} />}
          </Content>
        </Layout>
      </AntApp>
    </ConfigProvider>
  );
}

function OverviewView({ overview, runs, columns }: {
  overview: ObservabilityOverview | null;
  runs: ObservabilityRun[];
  columns: Array<Record<string, unknown>>;
}) {
  if (!overview && runs.length === 0) return <Empty description="暂无 Agent 执行记录" />;
  const rate = overview ? Math.round(overview.successRate * 100) : 0;
  return (
    <div className="observability-shell">
      <div className="observability-page-title">
        <div><Typography.Title level={2}>运行总览</Typography.Title>
          <Typography.Text type="secondary">从任务 Run 到 Agent、工具和质量门禁的统一执行视图</Typography.Text></div>
        <div className="observability-window"><span>数据窗口</span>
          <strong>{formatWindow(overview?.windowStart ?? null, overview?.windowEnd ?? null)}</strong></div>
      </div>
      <Row gutter={[12, 12]} className="observability-metrics">
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<BranchesOutlined />} label="Runs" value={overview?.runCount ?? runs.length} suffix="次" /></Col>
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<ThunderboltOutlined />} label="成功率" value={rate} suffix="%" progress={rate} /></Col>
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<ClockCircleOutlined />} label="P50 / P95" value={formatMs(overview?.p50DurationMs ?? 0) + " / " + formatMs(overview?.p95DurationMs ?? 0)} /></Col>
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<WarningOutlined />} label="失败 Span" value={overview?.failedSpanCount ?? 0} suffix="个" danger /></Col>
      </Row>
      <div className="observability-section-heading">
        <div><Typography.Title level={4}>最近运行</Typography.Title><Typography.Text type="secondary">点击 Trace 查看完整阶段链路</Typography.Text></div>
        <Space size={18}><span>Tokens <strong>{(overview?.totalTokens ?? 0).toLocaleString()}</strong></span><span>观测丢弃 <strong>{overview?.droppedSpanWrites ?? 0}</strong></span></Space>
      </div>
      <Card className="observability-table-card" styles={{ body: { padding: 0 } }}>
        <Table<ObservabilityRun> rowKey="traceId" columns={columns as never} dataSource={runs}
          pagination={{ pageSize: 12, showSizeChanger: false }} locale={{ emptyText: "暂无运行记录" }} scroll={{ x: 900 }} />
      </Card>
    </div>
  );
}

function MetricCard({ icon, label, value, suffix, progress, danger }: {
  icon: ReactNode; label: string; value: number | string; suffix?: string; progress?: number; danger?: boolean;
}) {
  return (
    <Card className={"observability-metric" + (danger ? " is-danger" : "")}>
      <div className="observability-metric-icon">{icon}</div>
      <Statistic title={label} value={value} suffix={suffix} />
      {progress !== undefined && <Progress percent={progress} showInfo={false} strokeColor="#00bb7f" size="small" />}
    </Card>
  );
}

function TraceView({ spans, selectedSpan, onSelect }: {
  spans: ObservabilitySpan[]; selectedSpan: ObservabilitySpan | null; onSelect: (span: ObservabilitySpan) => void;
}) {
  const root = spans[0];
  const spanColumns = [
    {
      title: "阶段", dataIndex: "spanType", width: 126,
      render: (value: string, row: ObservabilitySpan) => (
        <button className="observability-span-cell" onClick={() => onSelect(row)}>
          <span className="observability-span-dot" style={{ background: spanColor(row.status) }} /><span>{value}</span>
        </button>
      ),
    },
    { title: "名称", dataIndex: "name", ellipsis: true },
    { title: "Agent / Skill", width: 190, render: (_: unknown, row: ObservabilitySpan) => <span>{row.agentId ?? "-"}{row.skillId ? " · " + row.skillId + "@" + (row.skillVersion ?? "?") : ""}</span> },
    { title: "耗时", dataIndex: "durationMs", width: 100, render: (value: number) => formatMs(value) },
    { title: "状态", dataIndex: "status", width: 92, render: (value: string) => statusTag(value) },
  ];
  return (
    <div className="observability-shell">
      <div className="observability-page-title">
        <div><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => { window.location.hash = "#/observability"; }}>运行列表</Button>
          <Typography.Title level={2}>Trace 详情</Typography.Title>
          <Typography.Text type="secondary" copyable>{root?.traceId ?? "未知 Trace"}</Typography.Text></div>
        <div className="observability-trace-summary">{root && statusTag(spans.some((s) => s.status === "FAILED") ? "FAILED" : root.status)}
          <span>{spans.length} 个 Span</span><span>{formatMs(spans.reduce((sum, span) => sum + span.durationMs, 0))} 累计</span></div>
      </div>
      {spans.length === 0 ? <Empty description="Trace 不存在或不属于当前账号" /> : (
        <Row gutter={[16, 16]} align="top">
          <Col xs={24} lg={15}><Card className="observability-table-card" styles={{ body: { padding: 0 } }}>
            <Table<ObservabilitySpan> rowKey="spanId" columns={spanColumns as never} dataSource={spans} pagination={false}
              size="middle" onRow={(record) => ({ onClick: () => onSelect(record) })}
              rowClassName={(record) => selectedSpan?.spanId === record.spanId ? "observability-selected-row" : ""} scroll={{ x: 680 }} />
          </Card></Col>
          <Col xs={24} lg={9}><Card className="observability-detail-card" title={selectedSpan?.name ?? "选择一个 Span"}>
            {selectedSpan ? <Space direction="vertical" size={16} style={{ width: "100%" }}>
              <div className="observability-detail-grid">
                <span>阶段</span><strong>{selectedSpan.spanType}</strong><span>状态</span><span>{statusTag(selectedSpan.status)}</span>
                <span>开始</span><span>{formatTime(selectedSpan.startedAt)}</span><span>耗时</span><strong>{formatMs(selectedSpan.durationMs)}</strong>
                <span>Agent</span><span>{selectedSpan.agentId ?? "-"}</span><span>模型槽位</span><span>{selectedSpan.modelSlotId ?? "-"}</span>
                <span>父 Span</span><span className="observability-mono">{selectedSpan.parentSpanId?.slice(0, 14) ?? "根节点"}</span>
              </div>
              {selectedSpan.errorSummary && <Alert type="error" showIcon title={selectedSpan.errorCode ?? "执行失败"} description={selectedSpan.errorSummary} />}
              {selectedSpan.inputSummary && <SummaryBlock label="输入摘要" value={selectedSpan.inputSummary} />}
              {selectedSpan.outputSummary && <SummaryBlock label="输出摘要" value={selectedSpan.outputSummary} />}
            </Space> : <Empty description="暂无 Span" />}
          </Card></Col>
        </Row>
      )}
    </div>
  );
}

function SummaryBlock({ label, value }: { label: string; value: string }) {
  return <div className="observability-summary-block"><div className="observability-summary-label">{label}</div>
    <Typography.Paragraph className="observability-summary-text" copyable={{ text: value }}>{value}</Typography.Paragraph></div>;
}
