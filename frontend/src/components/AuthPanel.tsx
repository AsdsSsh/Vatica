import { useState } from "react";
import { App, Button, Form, Input, Modal, Tabs, Typography } from "antd";
import { loginUser, registerUser, setAuthToken, type AuthResponse } from "../api";

/**
 * 登录/注册弹窗（迭代 13 I13-7）：成功后 JWT 存 localStorage，
 * 后续请求由 api.ts 统一带 Authorization 头。后端 vatica.auth.enabled=true 时生效。
 */
interface Props {
  open: boolean;
  onClose: () => void;
  onAuthChanged?: () => void;
}

export default function AuthPanel({ open, onClose, onAuthChanged }: Props) {
  const { message } = App.useApp();
  const [tab, setTab] = useState<"login" | "register">("login");
  const [busy, setBusy] = useState(false);
  const [loginForm] = Form.useForm<{ username: string; password: string }>();
  const [registerForm] = Form.useForm<{ username: string; password: string; orgName?: string }>();

  function finish(auth: AuthResponse) {
    setAuthToken(auth.token);
    message.success(`已登录：${auth.username}（${auth.role}）`);
    onClose();
    onAuthChanged?.();
  }

  async function login() {
    const values = await loginForm.validateFields();
    setBusy(true);
    try {
      finish(await loginUser(values.username, values.password));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function register() {
    const values = await registerForm.validateFields();
    setBusy(true);
    try {
      finish(await registerUser(values.username, values.password, values.orgName));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title="账号"
      open={open}
      onCancel={onClose}
      footer={null}
      width={380}
      destroyOnHidden
    >
      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as "login" | "register")}
        items={[
          {
            key: "login",
            label: "登录",
            children: (
              <Form form={loginForm} layout="vertical" onFinish={login}>
                <Form.Item name="username" label="用户名" rules={[{ required: true, message: "填用户名" }]}>
                  <Input autoComplete="username" />
                </Form.Item>
                <Form.Item name="password" label="密码" rules={[{ required: true, message: "填密码" }]}>
                  <Input.Password autoComplete="current-password" />
                </Form.Item>
                <Button type="primary" htmlType="submit" block loading={busy}>
                  登录
                </Button>
              </Form>
            ),
          },
          {
            key: "register",
            label: "注册",
            children: (
              <Form form={registerForm} layout="vertical" onFinish={register}>
                <Form.Item name="username" label="用户名" rules={[{ required: true, min: 3, message: "至少 3 个字符" }]}>
                  <Input autoComplete="username" />
                </Form.Item>
                <Form.Item name="password" label="密码" rules={[{ required: true, min: 6, message: "至少 6 位" }]}>
                  <Input.Password autoComplete="new-password" />
                </Form.Item>
                <Form.Item name="orgName" label="组织名（可选）">
                  <Input placeholder="我的组织" />
                </Form.Item>
                <Button type="primary" htmlType="submit" block loading={busy}>
                  注册
                </Button>
              </Form>
            ),
          },
        ]}
      />
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
        账号用于云端多客户端访问；未开启鉴权时仅做登录态保存。
      </Typography.Paragraph>
    </Modal>
  );
}
