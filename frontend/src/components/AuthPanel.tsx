import { useEffect, useState } from "react";
import { Alert, App, Button, Descriptions, Flex, Form, Input, Modal, Spin, Tabs, Tag, Typography } from "antd";
import { loginUser, registerUser, setAuthToken, type AuthResponse } from "../api";
import { useAuth } from "../auth";

const ROLE_LABELS: Record<string, string> = {
  PLATFORM_ADMIN: "平台管理员",
  ORG_ADMIN: "组织管理员",
  MEMBER: "成员",
};

function roleLabel(role: string): string {
  return ROLE_LABELS[role] ?? role;
}

function formatExpiry(expiresAt: string | null): string {
  if (!expiresAt) return "-";
  const date = new Date(expiresAt);
  return Number.isNaN(date.getTime()) ? expiresAt : date.toLocaleString();
}

/**
 * 登录/注册弹窗（迭代 13 I13-7；迭代 14.5 双态收口）：
 * - anonymous：显示登录/注册表单；
 * - authenticated：回显服务端 /api/auth/me 返回的用户名、组织、角色与 Token 到期时间，提供退出登录；
 * - local：后端未开启鉴权时明确显示本地学习模式。
 */
interface Props {
  open: boolean;
  onClose: () => void;
  onAuthChanged?: () => void;
}

export default function AuthPanel({ open, onClose, onAuthChanged }: Props) {
  const { message } = App.useApp();
  const { status, user, refresh, logout } = useAuth();
  const [tab, setTab] = useState<"login" | "register">("login");
  const [busy, setBusy] = useState(false);
  const [loginForm] = Form.useForm<{ username: string; password: string }>();
  const [registerForm] = Form.useForm<{ username: string; password: string; orgName?: string }>();

  // 迭代 14.5：账号弹窗每次打开都向服务端 /me 校验一次，不依赖上次内存态
  useEffect(() => {
    if (open) void refresh();
  }, [open, refresh]);

  useEffect(() => {
    if (open && status === "anonymous") {
      loginForm.resetFields();
      registerForm.resetFields();
      setTab("login");
    }
  }, [open, status, loginForm, registerForm]);

  function finish(auth: AuthResponse) {
    // /me 是最终身份事实源；这里先落 Token 触发统一刷新，弹窗随后自动切换到账号回显态
    setAuthToken(auth.token);
    setTab("login");
    loginForm.resetFields();
    registerForm.resetFields();
    message.success(`已登录：${auth.username}（${roleLabel(auth.role)}）`);
    onAuthChanged?.();
  }

  function handleLogout() {
    logout();
    loginForm.resetFields();
    registerForm.resetFields();
    message.success("已退出登录");
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
      width={400}
      destroyOnHidden
    >
      {status === "loading" ? (
        <Flex vertical align="center" gap={12} style={{ padding: "24px 0" }}>
          <Spin />
          <Typography.Text type="secondary">正在确认登录状态…</Typography.Text>
        </Flex>
      ) : status === "authenticated" && user ? (
        <Flex vertical gap={12}>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="用户名">{user.username}</Descriptions.Item>
            <Descriptions.Item label="组织">#{user.orgId ?? "-"}</Descriptions.Item>
            <Descriptions.Item label="角色">
              <Tag>{roleLabel(user.role)}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="Token 到期">{formatExpiry(user.expiresAt)}</Descriptions.Item>
          </Descriptions>
          <Alert type="success" showIcon message="当前账号的会话、任务、模型与工作台数据均已按组织/用户隔离" />
          <Flex justify="end" gap={8}>
            <Button danger onClick={handleLogout}>退出登录</Button>
            <Button type="primary" onClick={onClose}>完成</Button>
          </Flex>
        </Flex>
      ) : status === "local" ? (
        <Flex vertical gap={12}>
          <Alert
            type="info"
            showIcon
            message="本地学习模式"
            description="后端未开启鉴权（vatica.auth.enabled=false），当前使用本地默认身份，无需登录。"
          />
          <Button block onClick={onClose}>知道了</Button>
        </Flex>
      ) : (
        <>
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
            登录成功后账号页会回显当前用户；Token 失效时会自动清理并回到未登录态。
          </Typography.Paragraph>
        </>
      )}
    </Modal>
  );
}
