import { useEffect, useState } from "react";
import { App, Button, Form, Input, Modal, Space, Typography } from "antd";
import { getApiBase, setApiBase } from "../api";

/**
 * 服务设置（迭代 9 I9-4，前后端分离）：配置前端连接的后端 API 基地址——
 * 默认 http://localhost:8080（桌面版后端随壳以 sidecar 启动）；留空恢复默认。
 * 云端部署时填后端地址即可切换（P1-6 云端后端零代码切换）。保存即时生效。
 */
interface Props {
  open: boolean;
  onClose: () => void;
}

export default function ServerSettings({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [form] = Form.useForm<{ baseUrl: string }>();
  const [saving, setSaving] = useState(false);

  // 打开时回填当前生效值
  useEffect(() => {
    if (!open) return;
    form.setFieldsValue({ baseUrl: getApiBase() });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function save() {
    form
      .validateFields()
      .then((values) => {
        setSaving(true);
        setApiBase(values.baseUrl);
        message.success(`已保存，接口地址：${getApiBase()}`);
        setSaving(false);
        onClose();
      })
      .catch(() => {
        // 迭代 12 I12-9：校验失败由 Form 就地展示，避免未处理 rejection
      });
  }

  return (
    <Modal
      title="服务设置"
      open={open}
      onCancel={onClose}
      width={460}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={saving} onClick={save}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" style={{ marginTop: 12 }}>
        <Form.Item
          name="baseUrl"
          label="后端接口地址（API 基地址）"
          rules={[
            {
              pattern: /^https?:\/\/[^\s/]+(?::\d+)?$/,
              message: "填写完整地址，如 http://localhost:8080 或 https://api.example.com",
            },
          ]}
        >
          <Input placeholder="http://localhost:8080" allowClear />
        </Form.Item>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
          桌面版默认随壳启动本地后端（localhost:8080）；留空保存即恢复默认。
          前后端分离部署（如云端后端）时填后端地址，保存后立即生效。
        </Typography.Paragraph>
      </Form>
    </Modal>
  );
}
