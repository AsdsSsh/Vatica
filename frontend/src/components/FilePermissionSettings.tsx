import { useEffect, useState } from "react";
import { App, Button, Flex, Input, Modal, Select, Space, Switch, Table, Typography } from "antd";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import {
  clearPermissionPolicy,
  loadPermissionPolicy,
  savePermissionPolicy,
  type FilePermissionMode,
  type FilePermissionPolicy,
  type WorkspaceRoot,
} from "../permissions";

/**
 * 文件权限与工作区设置（迭代 11）：前端权限中心。
 * localStorage 是权限事实来源；保存后后续聊天/任务请求自动携带。
 */
interface Props {
  open: boolean;
  onClose: () => void;
}

export default function FilePermissionSettings({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [policy, setPolicy] = useState<FilePermissionPolicy>(loadPermissionPolicy);
  const [newPath, setNewPath] = useState("");

  useEffect(() => {
    if (open) {
      setPolicy(loadPermissionPolicy());
      setNewPath("");
    }
  }, [open]);

  function addRoot() {
    const path = newPath.trim();
    if (!path) return;
    if (policy.workspaceRoots.some((r) => r.path.trim() === path)) {
      message.warning("该目录已在工作区根列表中");
      return;
    }
    setPolicy((prev) => ({
      ...prev,
      workspaceRoots: [...prev.workspaceRoots, { path, read: true, write: true }],
    }));
    setNewPath("");
  }

  function updateRoot(index: number, update: Partial<WorkspaceRoot>) {
    setPolicy((prev) => ({
      ...prev,
      workspaceRoots: prev.workspaceRoots.map((r, i) => (i === index ? { ...r, ...update } : r)),
    }));
  }

  function removeRoot(index: number) {
    setPolicy((prev) => ({
      ...prev,
      workspaceRoots: prev.workspaceRoots.filter((_, i) => i !== index),
    }));
  }

  function save() {
    savePermissionPolicy(policy);
    message.success("文件权限已保存，立即生效");
    onClose();
  }

  return (
    <Modal
      title="文件权限与工作区"
      open={open}
      onCancel={onClose}
      width={720}
      footer={
        <Space>
          <Button
            onClick={() => {
              setPolicy(clearPermissionPolicy());
              message.success("已清空授权，恢复默认工作区");
            }}
          >
            清空授权
          </Button>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={save}>
            保存
          </Button>
        </Space>
      }
    >
      <Flex vertical gap={12}>
        <div>
          <Typography.Text strong>沙盒模式</Typography.Text>
          <Select<FilePermissionMode>
            value={policy.mode}
            onChange={(mode) => setPolicy((prev) => ({ ...prev, mode }))}
            style={{ width: "100%", marginTop: 4 }}
            options={[
              { value: "READ_ONLY", label: "read-only（只读：工作区内可读，写入需确认）" },
              { value: "WORKSPACE_WRITE", label: "workspace-write（工作区内读写自动放行，越界询问）" },
              { value: "DANGER_FULL_ACCESS", label: "danger-full-access（完全访问，谨慎开启）" },
            ]}
          />
        </div>

        <div>
          <Typography.Text strong>工作区根（可写目录）</Typography.Text>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
            后端启动目录始终是默认工作区；在此添加额外目录。任务创建时还可单独选择任务工作目录。
          </Typography.Paragraph>
          <Table<WorkspaceRoot>
            size="small"
            rowKey={(r) => r.path}
            pagination={false}
            dataSource={policy.workspaceRoots}
            locale={{ emptyText: "未添加额外目录（默认仅后端启动目录）" }}
            columns={[
              {
                title: "目录",
                dataIndex: "path",
                ellipsis: true,
                render: (path: string) => (
                  <Typography.Text style={{ fontSize: 12, wordBreak: "break-all" }}>{path}</Typography.Text>
                ),
              },
              {
                title: "读",
                width: 56,
                render: (_, root, index) => (
                  <Switch
                    size="small"
                    checked={root.read}
                    onChange={(v) => updateRoot(index, { read: v })}
                  />
                ),
              },
              {
                title: "写",
                width: 56,
                render: (_, root, index) => (
                  <Switch
                    size="small"
                    checked={root.write}
                    onChange={(v) => updateRoot(index, { write: v })}
                  />
                ),
              },
              {
                title: "",
                width: 48,
                render: (_, _root, index) => (
                  <Button
                    size="small"
                    type="link"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() => removeRoot(index)}
                  />
                ),
              },
            ]}
          />
          <Flex gap={8} style={{ marginTop: 8 }}>
            <Input
              placeholder="输入绝对路径，如 D:\\docs 或 C:\\Users\\you\\Desktop\\project"
              value={newPath}
              onChange={(e) => setNewPath(e.target.value)}
              onPressEnter={addRoot}
            />
            <Button icon={<PlusOutlined />} onClick={addRoot}>
              添加
            </Button>
          </Flex>
        </div>
      </Flex>
    </Modal>
  );
}
