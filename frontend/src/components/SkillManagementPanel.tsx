import { useEffect, useState } from "react";
import { App, Button, Empty, Flex, Modal, Select, Space, Switch, Table, Tag, Tooltip, Typography } from "antd";
import { CheckOutlined, RollbackOutlined } from "@ant-design/icons";
import {
  activateSkillVersion,
  fetchSkills,
  isAuthExpiredError,
  rollbackSkill,
  setSkillEnabled,
  type SkillView,
} from "../api";

interface Props { open: boolean; onClose: () => void }

const roleNames: Record<string, string> = {
  document: "文档",
  pim: "个人事务",
  workspace: "工作区",
  research: "研究",
  general: "通用",
};

/** 迭代 20A/20D：内置 Skill 生命周期、权限能力和资源额度。 */
export default function SkillManagementPanel({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [skills, setSkills] = useState<SkillView[]>([]);
  const [selectedVersions, setSelectedVersions] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [changing, setChanging] = useState<string | null>(null);

  async function reload() {
    setLoading(true);
    try {
      const values = await fetchSkills();
      setSkills(values);
      setSelectedVersions(Object.fromEntries(values.map((skill) => [skill.id, skill.activeVersion])));
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (open) void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function replace(updated: SkillView) {
    setSkills((current) => current.map((skill) => skill.id === updated.id ? updated : skill));
    setSelectedVersions((current) => ({ ...current, [updated.id]: updated.activeVersion }));
  }

  async function changeEnabled(skill: SkillView, enabled: boolean) {
    setChanging(skill.id);
    try {
      replace(await setSkillEnabled(skill.id, enabled));
      message.success(enabled ? "Skill 已启用" : "Skill 已停用");
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setChanging(null);
    }
  }

  async function activate(skill: SkillView) {
    const version = selectedVersions[skill.id];
    if (!version || version === skill.activeVersion) return;
    setChanging(skill.id);
    try {
      replace(await activateSkillVersion(skill.id, version));
      message.success(`已切换到 ${version}`);
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setChanging(null);
    }
  }

  async function rollback(skill: SkillView) {
    setChanging(skill.id);
    try {
      const updated = await rollbackSkill(skill.id);
      replace(updated);
      message.success(`已回滚到 ${updated.activeVersion}`);
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setChanging(null);
    }
  }

  return (
    <Modal title="Skills" open={open} onCancel={onClose} footer={null} width={940} destroyOnHidden>
      <Table<SkillView>
        rowKey="id"
        size="small"
        loading={loading}
        pagination={false}
        dataSource={skills}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无 Skill" /> }}
        scroll={{ x: 760 }}
        expandable={{
          expandedRowRender: (skill) => (
            <Space direction="vertical" size={8} style={{ width: "100%" }}>
              <Typography.Text type="secondary">{skill.description}</Typography.Text>
              <Flex gap={6} wrap>
                <Typography.Text type="secondary">工具</Typography.Text>
                {skill.tools.map((tool) => <Tag key={tool}>{tool}</Tag>)}
              </Flex>
              <Flex gap={6} wrap>
                <Typography.Text type="secondary">权限</Typography.Text>
                {skill.permissions.map((permission) => <Tag color="gold" key={permission}>{permission}</Tag>)}
              </Flex>
              <Flex gap={6} wrap>
                <Typography.Text type="secondary">资源额度</Typography.Text>
                <Tag>推理 {skill.limits.maxIterations} 轮</Tag>
                <Tag>工具 {skill.limits.maxToolCalls} 次</Tag>
                <Tag>输出 {skill.limits.maxOutputChars} 字符</Tag>
              </Flex>
            </Space>
          ),
        }}
        columns={[
          {
            title: "Skill",
            dataIndex: "displayName",
            width: 190,
            render: (_, skill) => (
              <Space direction="vertical" size={0}>
                <Typography.Text strong>{skill.displayName}</Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{skill.id}</Typography.Text>
              </Space>
            ),
          },
          {
            title: "Agent",
            dataIndex: "agentRole",
            width: 100,
            render: (role: string) => <Tag>{roleNames[role] ?? role}</Tag>,
          },
          {
            title: "状态",
            dataIndex: "enabled",
            width: 92,
            render: (_, skill) => (
              <Switch
                size="small"
                checked={skill.enabled}
                loading={changing === skill.id}
                disabled={!skill.manageable || (changing !== null && changing !== skill.id)}
                onChange={(checked) => void changeEnabled(skill, checked)}
              />
            ),
          },
          {
            title: "版本",
            width: 230,
            render: (_, skill) => (
              <Flex gap={6} align="center">
                <Select
                  size="small"
                  value={selectedVersions[skill.id] ?? skill.activeVersion}
                  disabled={!skill.manageable || changing !== null}
                  style={{ width: 112 }}
                  onChange={(version) => setSelectedVersions((current) => ({ ...current, [skill.id]: version }))}
                  options={skill.versions.map((version) => ({
                    value: version.version,
                    label: `${version.version}${version.latest ? " · 最新" : ""}`,
                  }))}
                />
                <Button
                  size="small"
                  icon={<CheckOutlined />}
                  disabled={!skill.manageable || changing !== null
                    || (selectedVersions[skill.id] ?? skill.activeVersion) === skill.activeVersion}
                  onClick={() => void activate(skill)}
                >
                  应用
                </Button>
              </Flex>
            ),
          },
          {
            title: "回滚",
            width: 70,
            align: "center",
            render: (_, skill) => (
              <Tooltip title={skill.canRollback ? `回滚到 ${skill.previousVersion}` : "没有可回滚版本"}>
                <Button
                  type="text"
                  size="small"
                  aria-label={`回滚 ${skill.displayName}`}
                  icon={<RollbackOutlined />}
                  loading={changing === skill.id}
                  disabled={!skill.manageable || !skill.canRollback || changing !== null}
                  onClick={() => void rollback(skill)}
                />
              </Tooltip>
            ),
          },
        ]}
      />
    </Modal>
  );
}
