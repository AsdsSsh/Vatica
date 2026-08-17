import { useEffect, useState } from "react";
import { App, Button, Flex, Modal, Select, Space, Switch, Typography } from "antd";
import { CloudServerOutlined } from "@ant-design/icons";
import { fetchPermissionPolicy, isAuthExpiredError, saveServerPermissionPolicy } from "../api";
import {
  DEFAULT_PERMISSION_POLICY,
  loadPermissionPolicy,
  savePermissionPolicy,
  type FilePermissionMode,
  type FilePermissionPolicy,
} from "../permissions";

interface Props {
  open: boolean;
  onClose: () => void;
}

/** 迭代 14：服务端是权限事实来源；localStorage 只保留离线兼容快照。 */
export default function FilePermissionSettings({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [policy, setPolicy] = useState<FilePermissionPolicy>(loadPermissionPolicy);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    fetchPermissionPolicy()
      .then((value) => {
        setPolicy(value);
        savePermissionPolicy(value);
      })
      .catch(() => setPolicy(loadPermissionPolicy()))
      .finally(() => setLoading(false));
  }, [open]);

  async function save() {
    setLoading(true);
    try {
      const saved = await saveServerPermissionPolicy(policy);
      savePermissionPolicy(saved);
      setPolicy(saved);
      message.success("文件权限已保存到服务端");
      onClose();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  const root = policy.workspaceRoots[0];
  return (
    <Modal
      title="文件权限与云工作区"
      open={open}
      onCancel={onClose}
      width={620}
      confirmLoading={loading}
      footer={<Space><Button onClick={onClose}>取消</Button><Button type="primary" onClick={() => void save()}>保存</Button></Space>}
    >
      <Flex vertical gap={16}>
        <div>
          <Typography.Text strong>沙盒模式</Typography.Text>
          <Select<FilePermissionMode>
            value={policy.mode}
            onChange={(mode) => setPolicy((prev) => ({ ...prev, mode }))}
            style={{ width: "100%", marginTop: 6 }}
            options={[
              { value: "READ_ONLY", label: "只读" },
              { value: "WORKSPACE_WRITE", label: "工作区读写" },
              { value: "DANGER_FULL_ACCESS", label: "完整工作区访问" },
            ]}
          />
        </div>
        <Flex gap={10} align="center">
          <CloudServerOutlined />
          <div style={{ minWidth: 0, flex: 1 }}>
            <Typography.Text strong>个人云工作区</Typography.Text>
            <Typography.Paragraph type="secondary" ellipsis style={{ margin: 0, fontSize: 12 }}>
              {root?.path ?? "登录后由服务端分配"}
            </Typography.Paragraph>
          </div>
          <Typography.Text>读</Typography.Text>
          <Switch checked={root?.read ?? true} onChange={(read) => setPolicy((prev) => ({
            ...prev,
            workspaceRoots: [{ path: root?.path ?? "", read, write: root?.write ?? true }],
          }))} />
          <Typography.Text>写</Typography.Text>
          <Switch checked={root?.write ?? true} disabled={policy.mode === "READ_ONLY"} onChange={(write) => setPolicy((prev) => ({
            ...prev,
            workspaceRoots: [{ path: root?.path ?? "", read: root?.read ?? true, write }],
          }))} />
        </Flex>
        <Button onClick={() => setPolicy(structuredClone(DEFAULT_PERMISSION_POLICY))}>恢复默认</Button>
      </Flex>
    </Modal>
  );
}
