import { useEffect, useState } from "react";
import {
  App,
  Button,
  Divider,
  Empty,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import { DeleteOutlined, EditOutlined, PlusOutlined, ThunderboltOutlined } from "@ant-design/icons";
import {
  fetchModelSlots,
  fetchAgentBindings,
  fetchEvaluationReport,
  fetchReliabilityBaseline,
  fetchUsageToday,
  isAuthExpiredError,
  MODEL_CONFIG_UPDATED_EVENT,
  MODEL_CAPABILITIES,
  saveModelSlots,
  saveAgentBinding,
  testModelConnection,
  type ModelSlot,
  type AgentBindingSettings,
  type EvaluationReport,
  type ReliabilityView,
  type UsageToday,
} from "../api";

/**
 * 模型设置（迭代 8.5 模型配置中心）：图形界面管理模型槽位——
 * 支持 OpenAI 兼容协议（DeepSeek/通义/Kimi/Ollama 等）与 Anthropic 协议（Claude）。
 * 保存即生效（无需重启）；"测试连接"用的是当前编辑内容，无需先保存。
 */

/** 空槽位模板（新增时按协议给默认端点；默认承担全部角色能力）。 */
function blankSlot(protocol: ModelSlot["protocol"]): ModelSlot {
  return {
    id: "",
    name: "",
    protocol,
    baseUrl: protocol === "openai" ? "https://api.deepseek.com" : "https://api.anthropic.com",
    apiKey: "",
    model: "",
    temperature: 0.7,
    enabled: true,
    capabilities: [...MODEL_CAPABILITIES],
    promptCacheKey: "",
    apiKeySet: false,
    apiKeyHint: null,
  };
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** 保存成功后通知外部（刷新对话区模型选择器）。 */
  onSaved: () => void;
}

export default function ModelSettings({ open, onClose, onSaved }: Props) {
  const { message, modal } = App.useApp();
  const [slots, setSlots] = useState<ModelSlot[]>([]);
  const [editing, setEditing] = useState<ModelSlot | null>(null);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [bindingSettings, setBindingSettings] = useState<AgentBindingSettings | null>(null);
  const [bindingScope, setBindingScope] = useState<"USER" | "ORG" | "PLATFORM">("PLATFORM");
  const [bindingSaving, setBindingSaving] = useState<string | null>(null);
  const [usageToday, setUsageToday] = useState<UsageToday | null>(null);
  const [reliability, setReliability] = useState<ReliabilityView | null>(null);
  const [evaluationReport, setEvaluationReport] = useState<EvaluationReport | null>(null);
  const [form] = Form.useForm<ModelSlot>();
  // 迭代 12 I12-9：dirty 跟踪——打开时快照，任何槽位变更后取消需确认
  const [baseline, setBaseline] = useState("");
  const dirty = JSON.stringify(slots) !== baseline;

  // 打开时拉取当前配置
  useEffect(() => {
    if (!open) return;
    fetchModelSlots()
      .then((list) => {
        setSlots(list);
        setBaseline(JSON.stringify(list));
      })
      .catch((e: Error) => {
        if (!isAuthExpiredError(e)) message.error(`读取模型配置失败：${e.message}`);
      });
    fetchAgentBindings()
      .then(setBindingSettings)
      .catch((e: Error) => {
        // 非管理员仍可使用槽位编辑器；Agent 绑定设置按后端权限自然隐藏错误。
        if (!isAuthExpiredError(e)) message.warning(`读取 Agent 模型绑定失败：${e.message}`);
      });
    fetchUsageToday().then(setUsageToday).catch(() => setUsageToday(null));
    fetchReliabilityBaseline().then(setReliability).catch(() => setReliability(null));
    fetchEvaluationReport().then(setEvaluationReport).catch(() => setEvaluationReport(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  async function changeAgentBinding(agentId: string, slotId: string | null) {
    const key = `${bindingScope}:${agentId}`;
    setBindingSaving(key);
    try {
      const saved = await saveAgentBinding({ scope: bindingScope, agentId, slotId });
      setBindingSettings((current) => {
        if (!current) return current;
        const next = current.bindings.filter(
          (binding) => !(binding.scope === saved.scope && binding.agentId === saved.agentId),
        );
        if (saved.slotId) next.push(saved);
        return { ...current, bindings: next };
      });
      message.success(slotId ? "Agent 模型绑定已生效" : "已恢复跟随默认模型");
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error(`保存 Agent 绑定失败：${(e as Error).message}`);
    } finally {
      setBindingSaving(null);
    }
  }

  function renderAgentBindings() {
    if (!bindingSettings) {
      return <Empty description="暂无 Agent 模型绑定数据" />;
    }
    const byAgent = new Map(
      bindingSettings.bindings
        .filter((binding) => binding.scope === bindingScope)
        .map((binding) => [binding.agentId, binding]),
    );
    return (
      <Space direction="vertical" size={12} style={{ width: "100%" }}>
        <Flex justify="space-between" align="center">
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            绑定按用户 → 组织 → 平台逐级解析；清空即跟随能力标签和全局默认。
          </Typography.Text>
          <Select
            size="small"
            value={bindingScope}
            style={{ width: 150 }}
            options={[
              { value: "PLATFORM", label: "平台级" },
              { value: "ORG", label: "组织级" },
              { value: "USER", label: "用户级" },
            ]}
            onChange={setBindingScope}
          />
        </Flex>
        <Table
          size="small"
          rowKey="id"
          pagination={false}
          dataSource={bindingSettings.agents}
          columns={[
            {
              title: "Agent",
              dataIndex: "id",
              width: 180,
              render: (id: string, agent: AgentBindingSettings["agents"][number]) => (
                <Space direction="vertical" size={0}>
                  <Typography.Text strong>{agent.role}</Typography.Text>
                  <Typography.Text type="secondary" className="vatica-mono" style={{ fontSize: 11 }}>
                    {id}
                  </Typography.Text>
                </Space>
              ),
            },
            { title: "能力标签", dataIndex: "modelCapability", width: 130 },
            {
              title: "模型槽位",
              render: (_: unknown, agent: AgentBindingSettings["agents"][number]) => {
                const binding = byAgent.get(agent.id);
                const key = `${bindingScope}:${agent.id}`;
                return (
                  <Select
                    allowClear
                    placeholder="跟随默认"
                    style={{ width: 270 }}
                    value={binding?.slotId ?? undefined}
                    loading={bindingSaving === key}
                    options={bindingSettings.slots.map((slot) => ({
                      value: slot.id,
                      disabled: !slot.enabled || !slot.credentialAvailable,
                      label: (
                        <Flex justify="space-between" gap={12}>
                          <span>{slot.name || slot.id}</span>
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            {slot.credentialAvailable ? slot.model : "凭据不可用"}
                          </Typography.Text>
                        </Flex>
                      ),
                    }))}
                    onChange={(value: string | undefined) => void changeAgentBinding(agent.id, value ?? null)}
                  />
                );
              },
            },
            {
              title: "状态",
              width: 130,
              render: (_: unknown, agent: AgentBindingSettings["agents"][number]) => {
                const status = byAgent.get(agent.id)?.status ?? "FOLLOW_DEFAULT";
                return <Tag color={status === "READY" ? "success" : status === "FOLLOW_DEFAULT" ? "default" : "warning"}>
                  {status === "READY" ? "已绑定" : status === "FOLLOW_DEFAULT" ? "跟随默认" : status}
                </Tag>;
              },
            },
          ]}
        />
        <Divider style={{ margin: "4px 0" }} />
        <Flex justify="space-between" align="center">
          <Typography.Text strong>角色运行基准</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
            今日 · token / 耗时 / Judge 通过率
          </Typography.Text>
        </Flex>
        <Table
          size="small"
          rowKey="agentId"
          pagination={false}
          locale={{ emptyText: "今日还没有角色用量" }}
          dataSource={usageToday ? Object.values(usageToday.byRole) : []}
          columns={[
            { title: "角色", dataIndex: "role", render: (v: string, r: UsageToday["byRole"][string]) => `${v}（${r.agentId}）` },
            { title: "请求", dataIndex: "requests", width: 70 },
            { title: "Tokens", dataIndex: "totalTokens", width: 100, render: (v: number) => v.toLocaleString() },
            { title: "耗时", dataIndex: "durationMs", width: 90, render: (v: number) => `${v} ms` },
            {
              title: "通过率", dataIndex: "passRate", width: 90,
              render: (v: number | null) => v == null ? "-" : `${Math.round(v * 100)}%`,
            },
          ]}
        />
        <Divider style={{ margin: "4px 0" }} />
        <Flex justify="space-between" align="center">
          <Typography.Text strong>运行时稳定性基线</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
            全部任务 · 质量 / 耗时 / 多次执行
          </Typography.Text>
        </Flex>
        <Table
          size="small"
          rowKey="runtime"
          pagination={false}
          locale={{ emptyText: "还没有可对照的执行任务" }}
          dataSource={reliability?.runtimes ?? []}
          columns={[
            { title: "运行时", dataIndex: "runtime", width: 110 },
            { title: "任务", dataIndex: "taskCount", width: 70 },
            {
              title: "通过率", dataIndex: "passRate", width: 90,
              render: (v: number | null) => v == null ? "-" : `${Math.round(v * 100)}%`,
            },
            {
              title: "平均评分", dataIndex: "averageScore", width: 90,
              render: (v: number | null) => v == null ? "-" : v.toFixed(1),
            },
            {
              title: "平均耗时", dataIndex: "averageDurationMs", width: 110,
              render: (v: number | null) => v == null ? "-" : `${Math.round(v)} ms`,
            },
            { title: "Token", dataIndex: "totalTokens", width: 90 },
            { title: "工具调用", dataIndex: "toolCalls", width: 90 },
            { title: "多次执行", dataIndex: "multiAttemptTasks", width: 90 },
          ]}
        />
      </Space>
    );
  }

  function renderEvaluationReport() {
    const thresholds = evaluationReport?.thresholds;
    const gateColor = { PENDING: "default", PASS: "success", FAIL: "error" } as const;
    const gateLabel = { PENDING: "样本不足", PASS: "通过", FAIL: "未通过" } as const;
    return (
      <Space direction="vertical" size={12} style={{ width: "100%" }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {thresholds
            ? `每个用例 ${thresholds.minSamplesPerCase} 个样本 · 通过率 ${Math.round(thresholds.minPassRate * 100)}% · 平均评分 ${thresholds.minAverageScore} · 工具失败率不高于 ${Math.round(thresholds.maxFailedToolRate * 100)}%`
            : "尚未读取评测门禁"}
        </Typography.Text>
        <Table
          size="small"
          rowKey="runtime"
          pagination={false}
          locale={{ emptyText: "还没有评测门禁数据" }}
          dataSource={evaluationReport?.gates ?? []}
          columns={[
            { title: "运行时", dataIndex: "runtime", width: 110 },
            {
              title: "门禁", dataIndex: "status", width: 100,
              render: (status: EvaluationReport["gates"][number]["status"], row: EvaluationReport["gates"][number]) => (
                <Tooltip title={row.reasons.length ? row.reasons.join("；") : "全部阈值已满足"}>
                  <Tag color={gateColor[status]}>{gateLabel[status]}</Tag>
                </Tooltip>
              ),
            },
            {
              title: "覆盖", width: 80,
              render: (_: unknown, row: EvaluationReport["gates"][number]) => `${row.coveredCases}/${row.totalCases}`,
            },
            { title: "样本", dataIndex: "terminalSamples", width: 70 },
            {
              title: "通过率", dataIndex: "passRate", width: 85,
              render: (value: number | null) => value == null ? "-" : `${Math.round(value * 100)}%`,
            },
            {
              title: "平均分", dataIndex: "averageScore", width: 80,
              render: (value: number | null) => value == null ? "-" : value.toFixed(1),
            },
            {
              title: "工具失败", dataIndex: "failedToolRate", width: 90,
              render: (value: number | null) => value == null ? "-" : `${Math.round(value * 100)}%`,
            },
            { title: "Token", dataIndex: "totalTokens", width: 85 },
          ]}
        />
        <Divider style={{ margin: "2px 0" }} />
        <Table
          size="small"
          rowKey={(row) => `${row.runtime}:${row.caseId}`}
          pagination={false}
          scroll={{ x: 900 }}
          locale={{ emptyText: "还没有固定评测结果" }}
          dataSource={evaluationReport?.results ?? []}
          columns={[
            { title: "用例", dataIndex: "title", width: 120, fixed: "left" },
            { title: "运行时", dataIndex: "runtime", width: 100 },
            { title: "样本", dataIndex: "terminalSamples", width: 65 },
            {
              title: "通过率", dataIndex: "passRate", width: 80,
              render: (value: number | null) => value == null ? "-" : `${Math.round(value * 100)}%`,
            },
            {
              title: "平均分", dataIndex: "averageScore", width: 75,
              render: (value: number | null) => value == null ? "-" : value.toFixed(1),
            },
            {
              title: "平均耗时", dataIndex: "averageDurationMs", width: 105,
              render: (value: number | null) => value == null ? "-" : `${Math.round(value)} ms`,
            },
            { title: "Token", dataIndex: "totalTokens", width: 80 },
            {
              title: "工具调用", width: 90,
              render: (_: unknown, row: EvaluationReport["results"][number]) =>
                row.failedToolCalls ? `${row.toolCalls} / 失败 ${row.failedToolCalls}` : row.toolCalls,
            },
            {
              title: "估算成本", dataIndex: "costEstimate", width: 90,
              render: (value: number) => value ? value.toFixed(4) : "0",
            },
          ]}
        />
      </Space>
    );
  }

  // 编辑弹窗打开且 Form 挂载后再回填，避免 useForm 尚未连接 Form 的告警/丢值（迭代 10 I10-1）
  useEffect(() => {
    if (!editing) return;
    form.resetFields();
    form.setFieldsValue(editing);
  }, [editing, form]);

  function openEditor(slot: ModelSlot | null, index: number | null) {
    // 新增时 slot 为 null：必须写入空槽位模板，编辑弹窗的 open 条件才成立（迭代 10 I10-1）
    // 迭代 13：编辑已有槽位时表单 apiKey 置空 = "留空保持不变"，不显示完整 key
    const next = slot ? { ...slot, apiKey: "" } : blankSlot("openai");
    setEditing(next);
    setEditingIndex(index);
  }

  function closeEditor() {
    setEditing(null);
    setEditingIndex(null);
  }

  /** 编辑表单提交：写回本地列表（点"保存全部"才落盘）。 */
  function applyEditor() {
    form
      .validateFields()
      .then((values) => {
        const merged = { ...editing, ...values } as ModelSlot;
        // 迭代 13：编辑已有槽位且 key 留空 = 保持现有 key（null）；新增留空 = 未配置（""）
        const next = {
          ...merged,
          apiKey:
            editingIndex !== null && (merged.apiKey == null || merged.apiKey === "")
              ? null
              : (merged.apiKey ?? ""),
        };
        if (editingIndex === null) {
          setSlots((prev) => [...prev, next]);
        } else {
          setSlots((prev) => prev.map((s, i) => (i === editingIndex ? next : s)));
        }
        closeEditor();
      })
      .catch(() => {
        // 校验失败：错误已由 Form 就地展示，不额外打扰
      });
  }

  function removeSlot(index: number) {
    setSlots((prev) => prev.filter((_, i) => i !== index));
  }

  /** 迭代 12 I12-9：未保存变更时点取消需确认，避免静默丢弃。 */
  function handleCancel() {
    if (!dirty) {
      onClose();
      return;
    }
    modal.confirm({
      title: "放弃未保存的修改？",
      content: "模型配置的改动尚未保存，关闭后将丢失。",
      okText: "放弃修改",
      okButtonProps: { danger: true },
      cancelText: "继续编辑",
      onOk: onClose,
    });
  }

  async function saveAll() {
    setSaving(true);
    try {
      await saveModelSlots(slots);
      message.success("已保存，立即生效");
      window.dispatchEvent(new Event(MODEL_CONFIG_UPDATED_EVENT));
      onSaved();
      onClose();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error(`保存失败：${(e as Error).message}`);
    } finally {
      setSaving(false);
    }
  }

  async function testSlot(slot: ModelSlot) {
    setTestingId(slot.id);
    try {
      const result = await testModelConnection(slot);
      if (result.ok) {
        message.success(`${slot.name || slot.model} 连接正常，模型回复：${result.reply}`);
      } else {
        message.error(`连接失败：${result.error ?? "未知错误"}`);
      }
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error(`测试请求失败：${(e as Error).message}`);
    } finally {
      setTestingId(null);
    }
  }

  return (
    <>
      <Modal
        title="模型设置"
        open={open}
        onCancel={handleCancel}
        width={820}
        footer={
          <Flex justify="space-between" align="center">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              保存后立即生效，无需重启
            </Typography.Text>
            <Space>
              <Button onClick={handleCancel}>取消</Button>
              <Button type="primary" loading={saving} onClick={saveAll}>
                保存全部
              </Button>
            </Space>
          </Flex>
        }
      >
        <Tabs items={[{
          key: "slots",
          label: "模型槽位",
          children: (
            <>
              {slots.length === 0 ? (
                <Empty description="还没有模型，先添加一个">
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor(null, null)}>
                    添加模型
                  </Button>
                </Empty>
              ) : (
          <Table<ModelSlot>
            size="small"
            rowKey={(s) => s.id || s.model || JSON.stringify(s)}
            dataSource={slots}
            pagination={false}
            columns={[
              {
                title: "模型",
                dataIndex: "name",
                render: (name: string, s) => (
                  <Space direction="vertical" size={0}>
                    <Typography.Text strong>{name || "（未命名）"}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {s.id}
                    </Typography.Text>
                  </Space>
                ),
              },
              {
                title: "协议",
                dataIndex: "protocol",
                width: 110,
                render: (p: string) => (
                  <Tag color={p === "openai" ? "blue" : "purple"}>
                    {p === "openai" ? "OpenAI 兼容" : "Anthropic"}
                  </Tag>
                ),
              },
              { title: "模型 ID", dataIndex: "model", width: 160, ellipsis: true },
              {
                title: "密钥",
                width: 120,
                render: (_, s) =>
                  s.apiKeySet ? (
                    <Typography.Text type="secondary" className="vatica-mono" style={{ fontSize: 12 }}>
                      已配置 {s.apiKeyHint}
                    </Typography.Text>
                  ) : (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      未配置
                    </Typography.Text>
                  ),
              },
              { title: "温度", dataIndex: "temperature", width: 60 },
              {
                title: "启用",
                dataIndex: "enabled",
                width: 60,
                render: (enabled: boolean, _slot, index) => (
                  <Switch
                    size="small"
                    checked={enabled}
                    onChange={(v) =>
                      setSlots((prev) => prev.map((x, i) => (i === index ? { ...x, enabled: v } : x)))
                    }
                  />
                ),
              },
              {
                title: "操作",
                width: 200,
                render: (_, s, index) => (
                  <Space size={4}>
                    <Button
                      size="small"
                      type="link"
                      icon={<ThunderboltOutlined />}
                      loading={testingId === s.id}
                      onClick={() => testSlot(s)}
                    >
                      测试连接
                    </Button>
                    <Button
                      size="small"
                      type="link"
                      icon={<EditOutlined />}
                      onClick={() => openEditor(s, index)}
                    >
                      编辑
                    </Button>
                    <Tooltip title="删除（保存后生效）">
                      <Button
                        size="small"
                        type="link"
                        danger
                        aria-label="删除模型"
                        icon={<DeleteOutlined />}
                        onClick={() => removeSlot(index)}
                      />
                    </Tooltip>
                  </Space>
                ),
              },
            ]}
          />
              )}
              <Button
                type="dashed"
                icon={<PlusOutlined />}
                block
                style={{ marginTop: 12 }}
                onClick={() => openEditor(null, null)}
              >
                添加模型
              </Button>
            </>
          ),
        }, {
          key: "agents",
          label: "Agent 模型",
          children: renderAgentBindings(),
        }, {
          key: "evaluation",
          label: "评测门禁",
          children: renderEvaluationReport(),
        }]} />
      </Modal>

      {/* 槽位编辑弹窗 */}
      <Modal
        title={editingIndex === null ? "添加模型" : "编辑模型"}
        open={editing !== null}
        onCancel={closeEditor}
        onOk={applyEditor}
        okText="确定"
        destroyOnHidden
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Flex gap={12}>
            <Form.Item
              name="name"
              label="名称"
              rules={[{ required: true, message: "给这个模型起个名字" }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="如：DeepSeek V4 / Claude Sonnet" />
            </Form.Item>
            <Form.Item
              name="id"
              label="标识"
              tooltip="路由标识，英文小写唯一，如 deepseek"
              rules={[
                { required: true, message: "填一个路由标识" },
                { pattern: /^[a-z0-9-]+$/, message: "仅小写字母、数字与连字符" },
              ]}
              style={{ flex: 1 }}
            >
              <Input placeholder="deepseek" />
            </Form.Item>
          </Flex>
          <Form.Item name="protocol" label="协议" rules={[{ required: true }]}>
            <Select
              options={[
                {
                  value: "openai",
                  label: "OpenAI 兼容（DeepSeek / 通义 / Kimi / Ollama 本地等）",
                },
                { value: "anthropic", label: "Anthropic（Claude 及兼容端点）" },
              ]}
              onChange={(p: ModelSlot["protocol"]) => {
                const current = form.getFieldValue("baseUrl");
                // 切换协议且端点仍是旧协议的默认值时，换成新协议默认端点
                if (
                  !current ||
                  current === "https://api.deepseek.com" ||
                  current === "https://api.anthropic.com"
                ) {
                  form.setFieldValue(
                    "baseUrl",
                    p === "openai" ? "https://api.deepseek.com" : "https://api.anthropic.com",
                  );
                }
              }}
            />
          </Form.Item>
          <Form.Item
            name="baseUrl"
            label="Base URL"
            rules={[{ required: true, message: "填端点地址" }]}
          >
            <Input placeholder="https://api.deepseek.com" />
          </Form.Item>
          <Form.Item
            name="capabilities"
            label="角色能力"
            tooltip="该槽位可被自动路由承担的角色：聊天（快/深思）、规划、评测、摘要。未勾选时仅可手动选择。"
          >
            <Select
              mode="multiple"
              allowClear
              options={MODEL_CAPABILITIES.map((cap) => ({ value: cap, label: cap }))}
            />
          </Form.Item>
          <Form.Item
            name="promptCacheKey"
            label="Prompt 缓存前缀"
            tooltip="OpenAI 兼容端点可填写稳定 system prompt 的前缀标识（留空 = 不启用）"
          >
            <Input placeholder="如：vatica-system-v1" allowClear />
          </Form.Item>
          <Flex gap={12}>
            <Form.Item name="model" label="模型 ID" rules={[{ required: true, message: "填模型 ID" }]} style={{ flex: 1 }}>
              <Input placeholder="deepseek-v4-flash / claude-sonnet-4-6" />
            </Form.Item>
            <Form.Item name="temperature" label="温度" style={{ width: 130 }}>
              <InputNumber min={0} max={2} step={0.1} style={{ width: "100%" }} />
            </Form.Item>
          </Flex>
          <Form.Item
            name="apiKey"
            label="API Key"
            tooltip="本地端点（如 Ollama）可留空；编辑已有模型时留空 = 保持原 Key"
          >
            <Input.Password
              placeholder={editing?.apiKeySet ? `已配置（${editing.apiKeyHint}），留空保持不变` : "sk-…（本地端点可留空）"}
              autoComplete="new-password"
            />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
