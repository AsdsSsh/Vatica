import { useEffect, useState } from "react";
import {
  App,
  Button,
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
  Tag,
  Tooltip,
  Typography,
} from "antd";
import { DeleteOutlined, EditOutlined, PlusOutlined, ThunderboltOutlined } from "@ant-design/icons";
import {
  fetchModelSlots,
  saveModelSlots,
  testModelConnection,
  type ModelSlot,
} from "../api";

/**
 * 模型设置（迭代 8.5 模型配置中心）：图形界面管理模型槽位——
 * 支持 OpenAI 兼容协议（DeepSeek/通义/Kimi/Ollama 等）与 Anthropic 协议（Claude）。
 * 保存即生效（无需重启）；"测试连接"用的是当前编辑内容，无需先保存。
 */

/** 空槽位模板（新增时按协议给默认端点）。 */
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
  };
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** 保存成功后通知外部（刷新对话区模型选择器）。 */
  onSaved: () => void;
}

export default function ModelSettings({ open, onClose, onSaved }: Props) {
  const { message } = App.useApp();
  const [slots, setSlots] = useState<ModelSlot[]>([]);
  const [editing, setEditing] = useState<ModelSlot | null>(null);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<ModelSlot>();

  // 打开时拉取当前配置
  useEffect(() => {
    if (!open) return;
    fetchModelSlots()
      .then(setSlots)
      .catch((e) => message.error(`读取模型配置失败：${(e as Error).message}`));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function openEditor(slot: ModelSlot | null, index: number | null) {
    setEditing(slot);
    setEditingIndex(index);
    form.setFieldsValue(slot ?? blankSlot("openai"));
  }

  /** 编辑表单提交：写回本地列表（点"保存全部"才落盘）。 */
  function applyEditor() {
    form.validateFields().then((values) => {
      const next = { ...editing, ...values } as ModelSlot;
      if (editingIndex === null) {
        setSlots((prev) => [...prev, next]);
      } else {
        setSlots((prev) => prev.map((s, i) => (i === editingIndex ? next : s)));
      }
      setEditing(null);
      setEditingIndex(null);
    });
  }

  function removeSlot(index: number) {
    setSlots((prev) => prev.filter((_, i) => i !== index));
  }

  async function saveAll() {
    setSaving(true);
    try {
      await saveModelSlots(slots);
      message.success("已保存，立即生效");
      onSaved();
      onClose();
    } catch (e) {
      message.error(`保存失败：${(e as Error).message}`);
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
      message.error(`测试请求失败：${(e as Error).message}`);
    } finally {
      setTestingId(null);
    }
  }

  return (
    <>
      <Modal
        title="模型设置"
        open={open}
        onCancel={onClose}
        width={820}
        footer={
          <Flex justify="space-between" align="center">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              保存后立即生效，无需重启
            </Typography.Text>
            <Space>
              <Button onClick={onClose}>取消</Button>
              <Button type="primary" loading={saving} onClick={saveAll}>
                保存全部
              </Button>
            </Space>
          </Flex>
        }
      >
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
      </Modal>

      {/* 槽位编辑弹窗 */}
      <Modal
        title={editingIndex === null ? "添加模型" : "编辑模型"}
        open={editing !== null}
        onCancel={() => setEditing(null)}
        onOk={applyEditor}
        okText="确定"
        destroyOnClose
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
            tooltip="本地端点（如 Ollama）可留空"
          >
            <Input.Password placeholder="sk-…（本地端点可留空）" autoComplete="new-password" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
