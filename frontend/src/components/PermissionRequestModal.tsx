import { Button, Checkbox, Modal, Space, Typography } from "antd";
import type { FilePermissionRequest } from "../api";

/**
 * 文件权限请求弹窗（迭代 12 I12-6，U8）：ChatPanel / StepPanel 共用的唯一实现。
 * 交互规则：maskClosable=false + closable=false + keyboard=false——
 * 权限决定只能显式点"允许/拒绝"，避免误触遮罩/Esc 静默拒绝并中断工具调用。
 */
interface Props {
  request: FilePermissionRequest | null;
  deciding: boolean;
  remember: boolean;
  onRememberChange: (v: boolean) => void;
  onDecide: (approved: boolean) => void;
}

export default function PermissionRequestModal({
  request,
  deciding,
  remember,
  onRememberChange,
  onDecide,
}: Props) {
  return (
    <Modal
      open={request !== null}
      title="文件访问需要授权"
      maskClosable={false}
      closable={false}
      keyboard={false}
      footer={
        <Space>
          <Button danger onClick={() => onDecide(false)} disabled={deciding}>
            拒绝
          </Button>
          <Button type="primary" loading={deciding} onClick={() => onDecide(true)}>
            允许
          </Button>
        </Space>
      }
    >
      {request && (
        <div>
          <Typography.Paragraph>
            Agent 请求<b>{request.access === "WRITE" ? "写入" : "读取"}</b>以下路径：
          </Typography.Paragraph>
          <Typography.Paragraph code copyable className="vatica-mono" style={{ wordBreak: "break-all" }}>
            {request.path}
          </Typography.Paragraph>
          <Typography.Paragraph type="secondary">{request.description}</Typography.Paragraph>
          <Checkbox checked={remember} onChange={(e) => onRememberChange(e.target.checked)}>
            记住授权：当前任务/会话内不再询问该目录，并写入本机授权列表
          </Checkbox>
        </div>
      )}
    </Modal>
  );
}
