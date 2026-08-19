import { useEffect, useState } from "react";
import { App, Button, Empty, Flex, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from "antd";
import { DeleteOutlined, ImportOutlined, SearchOutlined } from "@ant-design/icons";
import {
  deleteKnowledgeDocument,
  fetchKnowledgeDocuments,
  importKnowledgeDocument,
  isAuthExpiredError,
  searchKnowledgeBase,
  type KnowledgeCitation,
  type KnowledgeDocumentView,
  type KnowledgeVisibility,
} from "../api";

interface Props { open: boolean; onClose: () => void }

const statusColor: Record<KnowledgeDocumentView["status"], string> = {
  INDEXING: "processing",
  READY: "success",
  FAILED: "error",
};

/** 迭代 19B：授权工作区文档索引、检索试跑和引用核对。 */
export default function KnowledgePanel({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [documents, setDocuments] = useState<KnowledgeDocumentView[]>([]);
  const [path, setPath] = useState("");
  const [visibility, setVisibility] = useState<KnowledgeVisibility>("PRIVATE");
  const [query, setQuery] = useState("");
  const [citations, setCitations] = useState<KnowledgeCitation[]>([]);
  const [busy, setBusy] = useState(false);

  async function reload() {
    try {
      setDocuments(await fetchKnowledgeDocuments());
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    }
  }

  useEffect(() => {
    if (open) void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  async function importDocument() {
    if (!path.trim()) return;
    setBusy(true);
    try {
      const document = await importKnowledgeDocument(path.trim(), visibility);
      if (document.status === "FAILED") {
        message.error(document.errorMessage ?? "索引失败");
      } else {
        message.success(`已索引 ${document.chunkCount} 个片段`);
        setPath("");
      }
      await reload();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function search() {
    if (!query.trim()) return;
    setBusy(true);
    try {
      setCitations((await searchKnowledgeBase(query.trim())).citations);
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function removeDocument(id: number) {
    try {
      await deleteKnowledgeDocument(id);
      await reload();
      message.success("知识库文档已删除");
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    }
  }

  return (
    <Modal title="知识库" open={open} onCancel={onClose} footer={null} width={820} destroyOnHidden>
      <Typography.Text type="secondary">从已授权工作区导入 .txt、.md 或 .docx</Typography.Text>
      <Flex gap={8} style={{ marginTop: 10, marginBottom: 16 }} wrap>
        <Input value={path} onChange={(e) => setPath(e.target.value)} placeholder="工作区内文件路径，例如 docs/产品方案.md" style={{ flex: "1 1 360px" }} />
        <Select<KnowledgeVisibility> value={visibility} onChange={setVisibility} style={{ width: 136 }} options={[
          { value: "PRIVATE", label: "仅自己" },
          { value: "ORG_SHARED", label: "组织共享" },
        ]} />
        <Button type="primary" icon={<ImportOutlined />} loading={busy} disabled={!path.trim()} onClick={() => void importDocument()}>导入</Button>
      </Flex>

      <Table<KnowledgeDocumentView>
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={documents}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无索引文档" /> }}
        columns={[
          { title: "文档", dataIndex: "sourceName", ellipsis: true },
          { title: "范围", dataIndex: "visibility", width: 92, render: (v) => v === "ORG_SHARED" ? "组织" : "自己" },
          { title: "状态", dataIndex: "status", width: 92, render: (v) => <Tag color={statusColor[v as KnowledgeDocumentView["status"]]}>{v}</Tag> },
          { title: "版本", dataIndex: "version", width: 66 },
          { title: "片段", dataIndex: "chunkCount", width: 66 },
          { title: "", width: 44, render: (_, row) => (
            <Popconfirm title="删除该文档及全部向量索引？" onConfirm={() => removeDocument(row.id)}>
              <Button type="text" danger aria-label={`删除 ${row.sourceName}`} icon={<DeleteOutlined />} />
            </Popconfirm>
          ) },
        ]}
      />

      <Typography.Title level={5} style={{ marginTop: 20 }}>检索试跑</Typography.Title>
      <Flex gap={8}>
        <Input value={query} onChange={(e) => setQuery(e.target.value)} onPressEnter={() => void search()} placeholder="输入要检索的问题" />
        <Button icon={<SearchOutlined />} loading={busy} disabled={!query.trim()} onClick={() => void search()}>检索</Button>
      </Flex>
      <Space direction="vertical" size={8} style={{ width: "100%", marginTop: 12 }}>
        {citations.map((item) => (
          <div key={item.citationId} style={{ borderTop: "1px solid var(--vatica-border)", paddingTop: 10 }}>
            <Flex justify="space-between" gap={12}>
              <Typography.Text strong>{item.citationId} · {item.documentName}</Typography.Text>
              <Typography.Text type="secondary">{item.score.toFixed(3)}</Typography.Text>
            </Flex>
            <Typography.Paragraph type="secondary" ellipsis={{ rows: 3, expandable: true }} style={{ marginBottom: 0, marginTop: 4 }}>
              {item.quote}
            </Typography.Paragraph>
          </div>
        ))}
      </Space>
    </Modal>
  );
}
