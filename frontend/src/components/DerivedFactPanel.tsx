import { useCallback, useEffect, useState } from "react";
import { Alert, App, Button, Flex, Tag, Typography } from "antd";
import { CheckOutlined, CloseOutlined } from "@ant-design/icons";
import {
  confirmContextFact,
  fetchContextFacts,
  revokeContextFact,
  type ContextFactView,
} from "../api";

interface Props {
  sessionId: string;
  /** 轮次结束后刷新（消息数变化即触发重取）。 */
  refreshKey: number;
}

/**
 * 迭代 34：待确认推断面板。只展示 AGENT_DERIVED + NEEDS_REFRESH 的记录——
 * 这些结论来自模型的异步抽取，用户确认前不会进入模型上下文。
 */
export default function DerivedFactPanel({ sessionId, refreshKey }: Props) {
  const { message } = App.useApp();
  const [pending, setPending] = useState<ContextFactView[]>([]);
  const [busyId, setBusyId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const all = await fetchContextFacts("CHAT_SESSION", sessionId, false);
      setPending(all.filter((fact) =>
        fact.status === "ACTIVE"
        && fact.trustLevel === "AGENT_DERIVED"
        && fact.verificationState === "NEEDS_REFRESH"));
    } catch {
      // 面板尽力而为：拉取失败不打断聊天。
    }
  }, [sessionId]);

  useEffect(() => {
    void refresh();
  }, [refresh, refreshKey]);

  const act = useCallback(async (fact: ContextFactView, confirm: boolean) => {
    setBusyId(fact.id);
    try {
      if (confirm) {
        await confirmContextFact(fact.id);
        void message.success("已确认：该事实将进入后续对话上下文");
      } else {
        await revokeContextFact(fact.id, "用户否决推断");
        void message.success("已否决该推断");
      }
      await refresh();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : "操作失败，请稍后重试");
    } finally {
      setBusyId(null);
    }
  }, [message, refresh]);

  if (pending.length === 0) {
    return null;
  }

  return (
    <div style={{ padding: "0 12px" }}>
      <Alert
        type="info"
        showIcon
        message={
          <Flex justify="space-between" align="center">
            <Typography.Text strong style={{ fontSize: 12 }}>
              模型推断（{pending.length}）· 确认后才会进入对话上下文
            </Typography.Text>
          </Flex>
        }
        description={
          <Flex vertical gap={6} style={{ marginTop: 4 }}>
            {pending.map((fact) => (
              <Flex key={fact.id} justify="space-between" align="center" gap={8}>
                <Flex align="center" gap={6} style={{ minWidth: 0, flex: 1 }}>
                  <Tag style={{ marginInlineEnd: 0 }}>{fact.factType}</Tag>
                  <Typography.Text
                    ellipsis
                    style={{ fontSize: 12 }}
                    title={`${fact.factKey}：${fact.displaySummary}`}
                  >
                    {fact.displaySummary}
                  </Typography.Text>
                </Flex>
                <Flex gap={4}>
                  <Button
                    size="small"
                    type="primary"
                    icon={<CheckOutlined />}
                    disabled={busyId !== null}
                    loading={busyId === fact.id}
                    onClick={() => void act(fact, true)}
                  >
                    确认
                  </Button>
                  <Button
                    size="small"
                    danger
                    icon={<CloseOutlined />}
                    disabled={busyId !== null}
                    loading={busyId === fact.id}
                    onClick={() => void act(fact, false)}
                  >
                    否决
                  </Button>
                </Flex>
              </Flex>
            ))}
          </Flex>
        }
      />
    </div>
  );
}
