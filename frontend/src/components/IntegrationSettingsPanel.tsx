import { useEffect, useState } from "react";
import { App, Flex, Form, Input, InputNumber, Modal, Select, Switch, Typography } from "antd";
import { fetchIntegrationSettings, saveIntegrationSettings, type IntegrationSettingsView } from "../api";

/**
 * 外部服务设置（迭代 13 I13-9）：AMAP / 邮件 / 数据库。
 * 密钥输入留空 = 保持现值；勾选"清除" = 删除该密钥。数据库与 AMAP 保存后重启生效。
 */
interface Props {
  open: boolean;
  onClose: () => void;
}

export default function IntegrationSettingsPanel({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [view, setView] = useState<IntegrationSettingsView | null>(null);
  const [busy, setBusy] = useState(false);
  const [clearAmap, setClearAmap] = useState(false);
  const [clearMail, setClearMail] = useState(false);
  const [clearDb, setClearDb] = useState(false);
  const [form] = Form.useForm();

  async function reload() {
    try {
      setView(await fetchIntegrationSettings());
    } catch (e) {
      message.error(`读取外部服务配置失败：${(e as Error).message}`);
    }
  }

  useEffect(() => {
    if (open) {
      void reload();
      form.resetFields();
      setClearAmap(false);
      setClearMail(false);
      setClearDb(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  async function save() {
    const values = await form.validateFields();
    setBusy(true);
    try {
      await saveIntegrationSettings({
        amap: { apiKey: clearAmap ? "" : values.amapKey || null },
        mail: {
          imapHost: values.imapHost ?? "",
          imapPort: values.imapPort ?? 993,
          smtpHost: values.smtpHost ?? "",
          smtpPort: values.smtpPort ?? 465,
          username: values.mailUsername ?? "",
          password: clearMail ? "" : values.mailPassword || null,
        },
        db: {
          mode: values.dbMode ?? "MYSQL",
          host: values.dbHost ?? "localhost",
          port: values.dbPort ?? 3306,
          database: values.dbDatabase ?? "vatica",
          username: values.dbUsername ?? "vatica",
          password: clearDb ? "" : values.dbPassword || null,
        },
      });
      message.success("已保存；AMAP 与数据库配置重启后端后生效");
      onClose();
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const secretPlaceholder = (set: boolean, hint: string | null) =>
    set ? `已配置（${hint ?? "…"}），留空保持不变` : "留空表示未配置";

  return (
    <Modal
      title="外部服务"
      open={open}
      onCancel={onClose}
      onOk={save}
      okText="保存"
      confirmLoading={busy}
      width={640}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          imapHost: view?.imapHost ?? "",
          imapPort: view?.imapPort ?? 993,
          smtpHost: view?.smtpHost ?? "",
          smtpPort: view?.smtpPort ?? 465,
          mailUsername: view?.mailUsername ?? "",
          dbMode: view?.dbMode ?? "MYSQL",
          dbHost: view?.dbHost ?? "localhost",
          dbPort: view?.dbPort ?? 3306,
          dbDatabase: view?.dbDatabase ?? "vatica",
          dbUsername: view?.dbUsername ?? "vatica",
        }}
        onValuesChange={() => undefined}
      >
        <Typography.Title level={5} style={{ marginTop: 0 }}>高德 MCP</Typography.Title>
        <Form.Item name="amapKey" label="AMAP Key">
          <Input.Password placeholder={secretPlaceholder(view?.amapKeySet ?? false, view?.amapKeyHint ?? null)} autoComplete="new-password" />
        </Form.Item>
        <Flex align="center" gap={8} style={{ marginTop: -8 }}>
          <Switch size="small" checked={clearAmap} onChange={setClearAmap} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>清除已保存的 AMAP Key</Typography.Text>
        </Flex>

        <Typography.Title level={5}>邮件（JavaMail）</Typography.Title>
        <Flex gap={12}>
          <Form.Item name="imapHost" label="IMAP 服务器" style={{ flex: 1 }}><Input placeholder="imap.qq.com" /></Form.Item>
          <Form.Item name="imapPort" label="IMAP 端口" style={{ width: 120 }}><InputNumber style={{ width: "100%" }} /></Form.Item>
        </Flex>
        <Flex gap={12}>
          <Form.Item name="smtpHost" label="SMTP 服务器" style={{ flex: 1 }}><Input placeholder="smtp.qq.com" /></Form.Item>
          <Form.Item name="smtpPort" label="SMTP 端口" style={{ width: 120 }}><InputNumber style={{ width: "100%" }} /></Form.Item>
        </Flex>
        <Form.Item name="mailUsername" label="邮箱账号"><Input placeholder="yourname@qq.com" /></Form.Item>
        <Form.Item name="mailPassword" label="授权码/密码">
          <Input.Password placeholder={secretPlaceholder(view?.mailPasswordSet ?? false, view?.mailPasswordHint ?? null)} autoComplete="new-password" />
        </Form.Item>
        <Flex align="center" gap={8} style={{ marginTop: -8 }}>
          <Switch size="small" checked={clearMail} onChange={setClearMail} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>清除已保存的邮件密码</Typography.Text>
        </Flex>

        <Typography.Title level={5}>数据库（保存后重启生效）</Typography.Title>
        <Form.Item name="dbMode" label="数据库模式">
          <Select options={[
            { value: "H2", label: "H2 本地文件（零依赖，默认推荐）" },
            { value: "MYSQL", label: "MySQL（云端/本机 MySQL）" },
          ]} />
        </Form.Item>
        <Flex gap={12}>
          <Form.Item name="dbHost" label="主机" style={{ flex: 1 }}><Input placeholder="localhost" /></Form.Item>
          <Form.Item name="dbPort" label="端口" style={{ width: 120 }}><InputNumber style={{ width: "100%" }} /></Form.Item>
        </Flex>
        <Form.Item name="dbDatabase" label="数据库名"><Input placeholder="vatica" /></Form.Item>
        <Form.Item name="dbUsername" label="用户名"><Input placeholder="vatica" /></Form.Item>
        <Form.Item name="dbPassword" label="密码">
          <Input.Password placeholder={secretPlaceholder(view?.dbPasswordSet ?? false, view?.dbPasswordHint ?? null)} autoComplete="new-password" />
        </Form.Item>
        <Flex align="center" gap={8} style={{ marginTop: -8 }}>
          <Switch size="small" checked={clearDb} onChange={setClearDb} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>清除已保存的数据库密码</Typography.Text>
        </Flex>
      </Form>
    </Modal>
  );
}
