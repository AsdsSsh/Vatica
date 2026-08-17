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
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from "antd";
import { DeleteOutlined, EditOutlined, PlusOutlined } from "@ant-design/icons";
import {
  createUserModelSlot,
  deleteUserModelSlot,
  fetchUserModelSlots,
  isAuthExpiredError,
  saveEphemeralUserModelKey,
  setUserModelCredentialMode,
  updateUserModelSlot,
  type UserModelSlotSaveRequest,
  type UserModelSlotView,
} from "../api";

/**
 * 我的模型（迭代 13 I13-4）：用户自配模型槽位。
 * EPHEMERAL = 仅本机/请求级；ENCRYPTED_AT_REST = 云端加密保存（可恢复）。
 */
interface Props {
  open: boolean;
  onClose: () => void;
  /** 增删改/开关变化后通知外层刷新模型选择器。 */
  onChanged?: () => void;
}

function blank(): UserModelSlotSaveRequest {
  return {
    name: "",
    protocol: "openai",
    baseUrl: "https://api.deepseek.com",
    model: "",
    temperature: 0.7,
    enabled: true,
    credentialMode: "EPHEMERAL",
    apiKey: "",
  };
}

export default function UserModelsPanel({ open, onClose, onChanged }: Props) {
  const { message, modal } = App.useApp();
  const [slots, setSlots] = useState<UserModelSlotView[]>([]);
  const [editing, setEditing] = useState<UserModelSlotView | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [busy, setBusy] = useState(false);
  const [modePrompt, setModePrompt] = useState<UserModelSlotView | null>(null);
  const [modeKey, setModeKey] = useState("");
  const [form] = Form.useForm<UserModelSlotSaveRequest>();

  async function reload() {
    try {
      setSlots(await fetchUserModelSlots());
    } catch (e) {
      if (isAuthExpiredError(e)) {
        setSlots([]);
      } else {
        message.error(`读取我的模型失败：${(e as Error).message}`);
      }
    }
  }

  useEffect(() => {
    if (open) {
      void reload();
      setEditing(null);
      setIsNew(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function openEditor(slot: UserModelSlotView | null) {
    setEditing(slot);
    setIsNew(slot === null);
    form.resetFields();
    form.setFieldsValue(
      slot
        ? { ...slot, apiKey: "" }
        : blank(),
    );
  }

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    try {
      const request: UserModelSlotSaveRequest = {
        ...values,
        // 编辑已有槽位且 key 留空 = 保持原 key
        apiKey: values.apiKey || null,
      };
      const mode = request.credentialMode;
      const enteredKey = request.apiKey?.trim() || null;
      if (isNew || !editing) {
        const created = await createUserModelSlot(request);
        // 迭代 13.5：EPHEMERAL 槽位的 key 保存在本机，聊天/任务请求时随请求发出
        if (created.credentialMode === "EPHEMERAL" && enteredKey) {
          saveEphemeralUserModelKey(created.id, enteredKey);
        }
      } else {
        await updateUserModelSlot(editing.id, request);
        if (mode === "EPHEMERAL" && enteredKey) {
          saveEphemeralUserModelKey(editing.id, enteredKey);
        } else if (mode === "ENCRYPTED_AT_REST") {
          // key 已转到云端加密保存，本机副本不再需要
          saveEphemeralUserModelKey(editing.id, null);
        }
      }
      message.success(isNew ? "已添加我的模型" : "已保存我的模型");
      setEditing(null);
      await reload();
      onChanged?.();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function switchMode(slot: UserModelSlotView, next: "EPHEMERAL" | "ENCRYPTED_AT_REST", apiKey: string | null) {
    setBusy(true);
    try {
      await setUserModelCredentialMode(slot.id, next, apiKey);
      // 迭代 13.5：转入云端加密保存后删除本机副本；转回 EPHEMERAL 时保留本机已有副本（如有）
      if (next === "ENCRYPTED_AT_REST") {
        saveEphemeralUserModelKey(slot.id, null);
      }
      message.success(next === "ENCRYPTED_AT_REST" ? "已开启云端加密保存" : "已关闭云端保存，云端密文已删除");
      await reload();
      onChanged?.();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  function confirmMode(slot: UserModelSlotView) {
    const next = slot.credentialMode === "EPHEMERAL" ? "ENCRYPTED_AT_REST" : "EPHEMERAL";
    if (next === "ENCRYPTED_AT_REST") {
      setModePrompt(slot);
      setModeKey("");
      return;
    }
    modal.confirm({
      title: "关闭云端加密保存？",
      content: "关闭后云端密文将立即删除，任务跨重启恢复能力失效。请确认本机仍保存了该 Key。",
      okText: "关闭",
      okButtonProps: { danger: true },
      cancelText: "取消",
      onOk: () => switchMode(slot, next, null),
    });
  }

  return (
    <>
      <Modal
        title="我的模型"
        open={open}
        onCancel={onClose}
        width={860}
        footer={
          <Flex justify="space-between" align="center">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              EPHEMERAL = 仅本机使用；ENCRYPTED_AT_REST = 云端加密保存（任务重启可恢复）
            </Typography.Text>
            <Button type="primary" onClick={() => openEditor(null)} icon={<PlusOutlined />}>
              添加模型
            </Button>
          </Flex>
        }
      >
        {slots.length === 0 ? (
          <Empty description="还没有我的模型，先添加一个（默认仅本机使用，不会上传 Key）" style={{ margin: "24px 0" }} />
        ) : (
          <Table<UserModelSlotView>
            size="small"
            rowKey="id"
            dataSource={slots}
            pagination={false}
            columns={[
              {
                title: "模型",
                render: (_, s) => (
                  <Space direction="vertical" size={0}>
                    <Typography.Text strong>{s.name}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {s.model} · {s.baseUrl}
                    </Typography.Text>
                  </Space>
                ),
              },
              { title: "协议", dataIndex: "protocol", width: 110, render: (p: string) => <Tag>{p}</Tag> },
              {
                title: "凭据模式",
                width: 150,
                render: (_, s) =>
                  s.credentialMode === "ENCRYPTED_AT_REST" ? (
                    <Tag color="gold">云端加密保存</Tag>
                  ) : (
                    <Tag>仅本机</Tag>
                  ),
              },
              {
                title: "密钥",
                width: 120,
                render: (_, s) =>
                  s.apiKeySet ? (
                    <Typography.Text type="secondary" className="vatica-mono" style={{ fontSize: 12 }}>
                      {s.apiKeyHint}
                    </Typography.Text>
                  ) : (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      未配置
                    </Typography.Text>
                  ),
              },
              {
                title: "启用",
                width: 60,
                render: (_, s) => <Switch size="small" checked={s.enabled} disabled />,
              },
              {
                title: "操作",
                width: 220,
                render: (_, s) => (
                  <Space size={4}>
                    <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openEditor(s)}>
                      编辑
                    </Button>
                    <Button size="small" type="link" onClick={() => confirmMode(s)}>
                      {s.credentialMode === "EPHEMERAL" ? "开启云端保存" : "关闭云端保存"}
                    </Button>
                    <Popconfirm
                      title="删除这个模型？"
                      onConfirm={async () => {
                        try {
                          await deleteUserModelSlot(s.id);
                          saveEphemeralUserModelKey(s.id, null);
                          message.success("已删除");
                          await reload();
                          onChanged?.();
                        } catch (e) {
                          if (!isAuthExpiredError(e)) message.error((e as Error).message);
                        }
                      }}
                    >
                      <Button size="small" type="link" danger icon={<DeleteOutlined />} aria-label="删除我的模型" />
                    </Popconfirm>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Modal>

      <Modal
        title={isNew ? "添加我的模型" : "编辑我的模型"}
        open={editing !== null}
        onCancel={() => setEditing(null)}
        onOk={save}
        okText="保存"
        confirmLoading={busy}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
          <Flex gap={12}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: "填名称" }]} style={{ flex: 1 }}>
              <Input placeholder="我的 DeepSeek" />
            </Form.Item>
            <Form.Item name="protocol" label="协议" rules={[{ required: true }]} style={{ flex: 1 }}>
              <Select
                options={[
                  { value: "openai", label: "OpenAI 兼容" },
                  { value: "anthropic", label: "Anthropic" },
                ]}
              />
            </Form.Item>
          </Flex>
          <Form.Item name="baseUrl" label="Base URL" rules={[{ required: true, message: "填 Base URL" }]}>
            <Input placeholder="https://api.deepseek.com" />
          </Form.Item>
          <Flex gap={12}>
            <Form.Item name="model" label="模型 ID" rules={[{ required: true, message: "填模型 ID" }]} style={{ flex: 1 }}>
              <Input placeholder="deepseek-v4-flash" />
            </Form.Item>
            <Form.Item name="temperature" label="温度" style={{ width: 130 }}>
              <InputNumber min={0} max={2} step={0.1} style={{ width: "100%" }} />
            </Form.Item>
          </Flex>
          <Form.Item name="credentialMode" label="凭据模式">
            <Select
              options={[
                { value: "EPHEMERAL", label: "仅本机（默认，Key 不上传云端）" },
                { value: "ENCRYPTED_AT_REST", label: "云端加密保存（任务重启可恢复）" },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label="API Key"
            tooltip="编辑时留空 = 保持原 Key；ENCRYPTED_AT_REST 模式新增时必填"
          >
            <Input.Password
              placeholder={editing?.apiKeySet ? `已配置（${editing.apiKeyHint}），留空保持不变` : "sk-…"}
              autoComplete="new-password"
            />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="开启云端加密保存"
        open={modePrompt !== null}
        onCancel={() => setModePrompt(null)}
        onOk={async () => {
          if (!modeKey.trim()) {
            message.error("必须输入 API Key");
            return;
          }
          await switchMode(modePrompt!, "ENCRYPTED_AT_REST", modeKey.trim());
          setModePrompt(null);
        }}
        okText="确认开启"
        confirmLoading={busy}
      >
        <Typography.Paragraph type="secondary">
          此 Key 将离开你的设备，以加密形式保存在 Vatica 云端数据库，用于服务重启后恢复任务。
          可随时关闭并删除云端副本。
        </Typography.Paragraph>
        <Input.Password
          placeholder="sk-…"
          value={modeKey}
          onChange={(e) => setModeKey(e.target.value)}
          autoComplete="new-password"
        />
      </Modal>
    </>
  );
}
