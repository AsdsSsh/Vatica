import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  ConfigProvider,
  Empty,
  Input,
  InputNumber,
  Layout,
  Progress,
  Row,
  Segmented,
  Space,
  Spin,
  Statistic,
  Table,
  Tag,
  Tooltip,
  Typography,
  Select,
  theme,
} from "antd";
import zhCN from "antd/locale/zh_CN";
import {
  ArrowLeftOutlined,
  BranchesOutlined,
  ClockCircleOutlined,
  DownloadOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import {
  fetchObservabilityOverview,
  fetchObservabilityDiagnostics,
  exportObservabilityDiagnostics,
  fetchObservabilityRunQuery,
  fetchObservabilityTrace,
  subscribeTaskEvents,
  type ObservabilityOverview,
  type ObservabilityDiagnosisReport,
  type ObservabilityRun,
  type ObservabilityRunQuery,
  type ObservabilityRunQueryPage,
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
  const [queryPage, setQueryPage] = useState<ObservabilityRunQueryPage | null>(null);
  const [query, setQuery] = useState<ObservabilityRunQuery>({ page: 0, size: 12, sortBy: "startedAt", direction: "desc" });
  const [spans, setSpans] = useState<ObservabilitySpan[]>([]);
  const [selectedSpan, setSelectedSpan] = useState<ObservabilitySpan | null>(null);
  const [diagnostics, setDiagnostics] = useState<ObservabilityDiagnosisReport | null>(null);
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
        const [nextSpans, nextDiagnostics] = await Promise.all([
          fetchObservabilityTrace(traceId), fetchObservabilityDiagnostics({ traceId }),
        ]);
        setSpans(nextSpans);
        setSelectedSpan(nextSpans[0] ?? null);
        setDiagnostics(nextDiagnostics);
      } else {
        const [nextOverview, nextQueryPage] = await Promise.all([
          fetchObservabilityOverview(20), fetchObservabilityRunQuery(query),
        ]);
        setOverview(nextOverview);
        setQueryPage(nextQueryPage);
        setDiagnostics(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "观测数据加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [traceId, query]);

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
              : traceId ? <TraceView spans={spans} selectedSpan={selectedSpan} diagnostics={diagnostics} onSelect={setSelectedSpan} />
                : <OverviewView overview={overview} queryPage={queryPage} query={query}
                  onQueryChange={(next) => setQuery({ ...next, page: 0 })}
                  onPageChange={(page, size) => setQuery((current) => ({ ...current, page, size }))}
                  onReset={() => setQuery({ page: 0, size: 12, sortBy: "startedAt", direction: "desc" })}
                  columns={columns} />}
          </Content>
        </Layout>
      </AntApp>
    </ConfigProvider>
  );
}

function OverviewView({ overview, queryPage, query, onQueryChange, onPageChange, onReset, columns }: {
  overview: ObservabilityOverview | null;
  queryPage: ObservabilityRunQueryPage | null;
  query: ObservabilityRunQuery;
  onQueryChange: (query: ObservabilityRunQuery) => void;
  onPageChange: (page: number, size: number) => void;
  onReset: () => void;
  columns: Array<Record<string, unknown>>;
}) {
  const runs = queryPage?.items ?? [];
  const aggregate = queryPage?.aggregate;
  if (!overview && runs.length === 0) return <Empty description="暂无 Agent 执行记录" />;
  const rate = aggregate ? Math.round(aggregate.successRate * 100) : Math.round((overview?.successRate ?? 0) * 100);
  return (
    <div className="observability-shell">
      <QueryControls query={query} onChange={onQueryChange} onReset={onReset} />
      <div className="observability-page-title">
        <div><Typography.Title level={2}>运行总览</Typography.Title>
          <Typography.Text type="secondary">从任务 Run 到 Agent、工具和质量门禁的统一执行视图</Typography.Text></div>
        <div className="observability-window"><span>数据窗口</span>
          <strong>{formatWindow(overview?.windowStart ?? null, overview?.windowEnd ?? null)}</strong></div>
      </div>
      <Row gutter={[12, 12]} className="observability-metrics">
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<BranchesOutlined />} label="筛选 Runs" value={aggregate?.runCount ?? overview?.runCount ?? runs.length} suffix="次" /></Col>
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<ThunderboltOutlined />} label="成功率" value={rate} suffix="%" progress={rate} /></Col>
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<ClockCircleOutlined />} label="P50 / P95" value={formatMs(aggregate?.p50DurationMs ?? overview?.p50DurationMs ?? 0) + " / " + formatMs(aggregate?.p95DurationMs ?? overview?.p95DurationMs ?? 0)} /></Col>
        <Col xs={24} sm={12} lg={6}><MetricCard icon={<WarningOutlined />} label="失败 Span" value={aggregate?.failedSpanCount ?? overview?.failedSpanCount ?? 0} suffix="个" danger /></Col>
      </Row>
      <div className="observability-section-heading">
        <div><Typography.Title level={4}>最近运行</Typography.Title><Typography.Text type="secondary">点击 Trace 查看完整阶段链路</Typography.Text></div>
        <Space size={18}><span>Tokens <strong>{(aggregate?.totalTokens ?? overview?.totalTokens ?? 0).toLocaleString()}</strong></span><span>成本 <strong>{(aggregate?.totalCost ?? overview?.totalCost ?? 0).toFixed(4)}</strong></span><span>观测丢弃 <strong>{overview?.droppedSpanWrites ?? 0}</strong></span></Space>
      </div>
      <Card className="observability-table-card" styles={{ body: { padding: 0 } }}>
        <Table<ObservabilityRun> rowKey="traceId" columns={columns as never} dataSource={runs}
          pagination={{ current: (queryPage?.page ?? 0) + 1, pageSize: queryPage?.size ?? 12,
            total: queryPage?.totalRuns ?? runs.length, showSizeChanger: true,
            onChange: (page, size) => onPageChange(page - 1, size) }}
          locale={{ emptyText: "暂无运行记录" }} scroll={{ x: 900 }} />
      </Card>
    </div>
  );
}

function QueryControls({ query, onChange, onReset }: {
  query: ObservabilityRunQuery;
  onChange: (query: ObservabilityRunQuery) => void;
  onReset: () => void;
}) {
  const patch = (value: Partial<ObservabilityRunQuery>) => onChange({ ...query, ...value });
  return <div className="observability-query-toolbar">
    <Input placeholder="任务 ID" value={query.taskId ?? ""} onChange={(e) => patch({ taskId: e.target.value || undefined })} allowClear style={{ width: 170 }} />
    <Input placeholder="Agent / 工具名称" value={query.name ?? ""} onChange={(e) => patch({ name: e.target.value || undefined })} allowClear style={{ width: 190 }} />
    <Select allowClear placeholder="状态" value={query.status} onChange={(value) => patch({ status: value })} style={{ width: 120 }} options={[
      { value: "SUCCESS", label: "成功" }, { value: "FAILED", label: "失败" }, { value: "CANCELLED", label: "已取消" }, { value: "OPEN", label: "进行中" },
    ]} />
    <Select allowClear placeholder="阶段" value={query.spanType} onChange={(value) => patch({ spanType: value })} style={{ width: 130 }} options={[
      { value: "TASK_RUN", label: "任务运行" }, { value: "AGENT", label: "Agent" }, { value: "MODEL", label: "模型" }, { value: "TOOL", label: "工具" }, { value: "HITL", label: "人工审批" }, { value: "JUDGE", label: "Judge" },
    ]} />
    <Input placeholder="Skill" value={query.skillId ?? ""} onChange={(e) => patch({ skillId: e.target.value || undefined })} allowClear style={{ width: 130 }} />
    <InputNumber min={0} placeholder="最少耗时 ms" value={query.minDurationMs} onChange={(value) => patch({ minDurationMs: value ?? undefined })} style={{ width: 130 }} />
    <InputNumber min={0} max={100} placeholder="最低 Judge" value={query.minJudgeScore} onChange={(value) => patch({ minJudgeScore: value ?? undefined })} style={{ width: 120 }} />
    <Select value={query.sortBy ?? "startedAt"} onChange={(value) => patch({ sortBy: value })} style={{ width: 130 }} options={[
      { value: "startedAt", label: "按开始时间" }, { value: "durationMs", label: "按耗时" }, { value: "totalTokens", label: "按 Tokens" }, { value: "costEstimate", label: "按成本" }, { value: "judgeScore", label: "按 Judge" },
    ]} />
    <Select value={query.direction ?? "desc"} onChange={(value) => patch({ direction: value })} style={{ width: 94 }} options={[{ value: "desc", label: "降序" }, { value: "asc", label: "升序" }]} />
    <Button icon={<ReloadOutlined />} onClick={onReset}>重置</Button>
  </div>;
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

function TraceView({ spans, selectedSpan, diagnostics, onSelect }: {
  spans: ObservabilitySpan[]; selectedSpan: ObservabilitySpan | null; diagnostics: ObservabilityDiagnosisReport | null; onSelect: (span: ObservabilitySpan) => void;
}) {
  const [viewMode, setViewMode] = useState<"waterfall" | "tree">("waterfall");
  const root = spans[0];
  async function exportReport() {
    if (!root?.traceId) return;
    try {
      const blob = await exportObservabilityDiagnostics(root.traceId);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a"); link.href = url; link.download = "vatica-diagnostics.md"; link.click();
      URL.revokeObjectURL(url);
    } catch { /* 统一错误提示由全局鉴权事件处理，导出失败不影响观测浏览 */ }
  }
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
        <div className="observability-trace-summary"><Button icon={<DownloadOutlined />} onClick={() => void exportReport()}>导出诊断</Button>{root && statusTag(spans.some((s) => s.status === "FAILED") ? "FAILED" : root.status)}
          <span>{spans.length} 个 Span</span><span>{formatMs(spans.reduce((sum, span) => sum + span.durationMs, 0))} 累计</span></div>
      </div>
      {spans.length === 0 ? <Empty description="Trace 不存在或不属于当前账号" /> : (
        <>
          <div className="observability-trace-mode"><Segmented value={viewMode} onChange={(value) => setViewMode(value as "waterfall" | "tree")} options={[
            { label: "时间瀑布", value: "waterfall" }, { label: "Span 树", value: "tree" },
          ]} /></div>
          <Row gutter={[16, 16]} align="top">
            <Col xs={24} lg={15}><Card className="observability-table-card" styles={{ body: { padding: 0 } }}>
              {viewMode === "waterfall" ? <TraceWaterfall spans={spans} selectedSpan={selectedSpan} onSelect={onSelect} />
                : <TraceTree spans={spans} selectedSpan={selectedSpan} onSelect={onSelect} />}
              <div className="observability-span-table-divider"><Typography.Text type="secondary">明细列表</Typography.Text></div>
              <Table<ObservabilitySpan> rowKey="spanId" columns={spanColumns as never} dataSource={spans} pagination={false}
                size="small" onRow={(record) => ({ onClick: () => onSelect(record) })}
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
          <DiagnosisPanel report={diagnostics} onSelect={(spanId) => { const span = spans.find((item) => item.spanId === spanId); if (span) onSelect(span); }} />
        </>
      )}
    </div>
  );
}

function DiagnosisPanel({ report, onSelect }: { report: ObservabilityDiagnosisReport | null; onSelect: (spanId: string) => void }) {
  if (!report) return null;
  return <Card className="observability-diagnosis-card" title="事实诊断" extra={<Typography.Text type="secondary">{report.findings.length} 条证据</Typography.Text>}>
    {report.findings.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前 Trace 未发现规则命中的慢点、失败或质量风险" />
      : <div className="observability-diagnosis-list">{report.findings.map((finding, index) => <button key={finding.spanId + finding.kind + index} className="observability-diagnosis-item" onClick={() => finding.spanId && onSelect(finding.spanId)}>
        <Tag color={finding.severity === "ERROR" ? "error" : finding.severity === "WARN" ? "warning" : "blue"}>{finding.kind}</Tag>
        <span><strong>{finding.title}</strong><small>{finding.evidence}</small></span>
      </button>)}</div>}
  </Card>;
}

function TraceWaterfall({ spans, selectedSpan, onSelect }: {
  spans: ObservabilitySpan[]; selectedSpan: ObservabilitySpan | null; onSelect: (span: ObservabilitySpan) => void;
}) {
  const start = Math.min(...spans.map((span) => new Date(span.startedAt).getTime()));
  const end = Math.max(...spans.map((span) => new Date(span.startedAt).getTime() + Math.max(1, span.durationMs)));
  const total = Math.max(1, end - start);
  return <div className="observability-waterfall" aria-label="Trace 时间瀑布">
    <div className="observability-waterfall-axis"><span>起点</span><span>{formatMs(total)}</span></div>
    {spans.slice().sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime()).map((span) => {
      const offset = Math.max(0, new Date(span.startedAt).getTime() - start) / total * 100;
      const width = Math.max(1.5, span.durationMs / total * 100);
      return <button key={span.spanId} className={"observability-waterfall-row" + (selectedSpan?.spanId === span.spanId ? " is-selected" : "")} onClick={() => onSelect(span)}>
        <span className="observability-waterfall-label"><i style={{ background: spanColor(span.status) }} />{span.spanType}<b>{span.name}</b></span>
        <span className="observability-waterfall-track"><i className="observability-waterfall-bar" style={{ left: offset + "%", width: width + "%", background: spanColor(span.status) }} /></span>
        <span className="observability-waterfall-duration">{formatMs(span.durationMs)}</span>
      </button>;
    })}
  </div>;
}

function TraceTree({ spans, selectedSpan, onSelect }: {
  spans: ObservabilitySpan[]; selectedSpan: ObservabilitySpan | null; onSelect: (span: ObservabilitySpan) => void;
}) {
  const children = new Map<string | null, ObservabilitySpan[]>();
  spans.forEach((span) => children.set(span.parentSpanId, [...(children.get(span.parentSpanId) ?? []), span]));
  const renderNode = (span: ObservabilitySpan, depth: number): ReactNode => <div key={span.spanId}>
    <button className={"observability-tree-node" + (selectedSpan?.spanId === span.spanId ? " is-selected" : "")} style={{ paddingLeft: 16 + depth * 22 }} onClick={() => onSelect(span)}>
      <span className="observability-tree-branch">{depth ? "↳" : "●"}</span><span className="observability-span-dot" style={{ background: spanColor(span.status) }} />
      <span className="observability-tree-name">{span.name}</span><span className="observability-tree-type">{span.spanType}</span><span className="observability-tree-duration">{formatMs(span.durationMs)}</span>
    </button>
    {(children.get(span.spanId) ?? []).sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime()).map((child) => renderNode(child, depth + 1))}
  </div>;
  const roots = spans.filter((span) => !span.parentSpanId || !spans.some((candidate) => candidate.spanId === span.parentSpanId));
  return <div className="observability-tree" aria-label="Trace Span 树">{roots.map((span) => renderNode(span, 0))}</div>;
}

function SummaryBlock({ label, value }: { label: string; value: string }) {
  return <div className="observability-summary-block"><div className="observability-summary-label">{label}</div>
    <Typography.Paragraph className="observability-summary-text" copyable={{ text: value }}>{value}</Typography.Paragraph></div>;
}
