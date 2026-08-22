import { useEffect, useState } from "react";
import { Alert, App, Button, Empty, Flex, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography } from "antd";
import { DeleteOutlined, ImportOutlined, ReloadOutlined, SearchOutlined } from "@ant-design/icons";
import {
  deleteKnowledgeDocument,
  fetchKnowledgeDocuments,
  fetchKnowledgeReadiness,
  importKnowledgeDocument,
  isAuthExpiredError,
  rebuildKnowledgeDocument,
  retryKnowledgeDocument,
  searchKnowledgeBase,
  type KnowledgeCitation,
  type KnowledgeDocumentView,
  type KnowledgeReadinessView,
  type KnowledgeSearchResult,
  type KnowledgeVisibility,
} from "../api";

interface Props { open: boolean; onClose: () => void }

const statusColor: Record<KnowledgeDocumentView["status"], string> = {
  INDEXING: "processing",
  READY: "success",
  FAILED: "error",
};

/** 迭代 19B/27B：授权工作区文档索引、失败恢复、重建和引用核对。 */
export default function KnowledgePanel({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [documents, setDocuments] = useState<KnowledgeDocumentView[]>([]);
  const [readiness, setReadiness] = useState<KnowledgeReadinessView | null>(null);
  const [path, setPath] = useState("");
  const [visibility, setVisibility] = useState<KnowledgeVisibility>("PRIVATE");
  const [query, setQuery] = useState("");
  const [citations, setCitations] = useState<KnowledgeCitation[]>([]);
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResult | null>(null);
  const [busy, setBusy] = useState(false);

  async function reload() {
    try {
      const [nextDocuments, nextReadiness] = await Promise.all([
        fetchKnowledgeDocuments(),
        fetchKnowledgeReadiness(),
      ]);
      setDocuments(nextDocuments);
      setReadiness(nextReadiness);
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
      const result = await searchKnowledgeBase(query.trim());
      setSearchResult(result);
      setCitations(result.citations);
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

  async function retryDocument(id: number) {
    setBusy(true);
    try {
      const document = await retryKnowledgeDocument(id);
      message[document.status === "READY" ? "success" : "warning"](
        document.status === "READY" ? `已恢复索引 ${document.chunkCount} 个片段` : (document.errorMessage ?? "索引未完成"),
      );
      await reload();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function rebuildDocument(id: number) {
    setBusy(true);
    try {
      const document = await rebuildKnowledgeDocument(id);
      message[document.status === "READY" ? "success" : "warning"](
        document.status === "READY" ? `已重建第 ${document.indexAttempt} 次索引` : (document.errorMessage ?? "重建未完成"),
      );
      await reload();
    } catch (e) {
      if (!isAuthExpiredError(e)) message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="知识库" open={open} onCancel={onClose} footer={null} width={820} destroyOnHidden>
      <Typography.Text type="secondary">从已授权工作区导入 .txt、.md 或 .docx</Typography.Text>
      {readiness && (
        <Alert
          style={{ marginTop: 12, marginBottom: 12 }}
          type={!readiness.postgres ? "warning" : readiness.ready ? (readiness.indexReady ? "success" : "info") : "error"}
          showIcon
          message={readiness.postgres ? (readiness.ready ? "PostgreSQL 知识索引状态" : "PostgreSQL 知识索引未就绪") : "当前为本地向量回退"}
          description={(
            <Space direction="vertical" size={2}>
              <span>{readiness.message ?? "等待索引状态检查。"}</span>
              <Typography.Text type="secondary">
                扩展 {readiness.extensionVersion ?? "未检测"} · Schema {readiness.schemaVersion ?? "未检测"} ·
                Embedding {readiness.embeddingModel ?? readiness.embeddingProvider ?? "未配置"} · {readiness.vectorDimensions} 维
              </Typography.Text>
              {readiness.configFingerprint && <Typography.Text type="secondary" copyable={{ text: readiness.configFingerprint }}>配置指纹 {readiness.configFingerprint.slice(0, 12)}…</Typography.Text>}
            </Space>
          )}
        />
      )}
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
          { title: "状态", dataIndex: "status", width: 120, render: (v, row) => (
            <Space size={4}>
              <Tag color={statusColor[v as KnowledgeDocumentView["status"]]}>{v}</Tag>
              {row.totalChunks > 0 && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.indexedChunks}/{row.totalChunks}</Typography.Text>}
            </Space>
          ) },
          { title: "版本", dataIndex: "version", width: 66 },
          { title: "片段", dataIndex: "chunkCount", width: 66 },
          { title: "操作", width: 142, render: (_, row) => (
            <Space size={0}>
              {row.status === "FAILED" && <Button type="text" aria-label={`重试 ${row.sourceName}`} icon={<ReloadOutlined />} onClick={() => void retryDocument(row.id)}>重试</Button>}
              {row.status === "READY" && <Popconfirm title="重新读取原文件并重建全部向量索引？" onConfirm={() => rebuildDocument(row.id)}>
                <Button type="text" aria-label={`重建 ${row.sourceName}`} icon={<ReloadOutlined />}>重建</Button>
              </Popconfirm>}
              <Popconfirm title="删除该文档及全部向量索引？" onConfirm={() => removeDocument(row.id)}>
                <Button type="text" danger aria-label={`删除 ${row.sourceName}`} icon={<DeleteOutlined />} />
              </Popconfirm>
            </Space>
          ) },
        ]}
      />

      <Typography.Title level={5} style={{ marginTop: 20 }}>检索试跑</Typography.Title>
      <Flex gap={8}>
        <Input value={query} onChange={(e) => setQuery(e.target.value)} onPressEnter={() => void search()} placeholder="输入要检索的问题" />
        <Button icon={<SearchOutlined />} loading={busy} disabled={!query.trim()} onClick={() => void search()}>检索</Button>
      </Flex>
      <Space direction="vertical" size={8} style={{ width: "100%", marginTop: 12 }}>
        {searchResult && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            仅检索当前用户私有资料与当前组织共享资料 · 向量索引 {searchResult.indexVersion}
          </Typography.Text>
        )}
        {citations.map((item) => (
          <div key={item.citationId} style={{ borderTop: "1px solid var(--vatica-border)", paddingTop: 10 }}>
            <Flex justify="space-between" gap={12}>
              <Typography.Text strong>{item.citationId} · {item.documentName}</Typography.Text>
              <Typography.Text type="secondary">{item.score.toFixed(3)}</Typography.Text>
            </Flex>
            <Typography.Text type="secondary" style={{ display: "block", fontSize: 12, marginTop: 4 }}>
              {item.sourcePath} · {item.sourceLocation} · 文档 v{item.documentVersion} · 索引 {item.indexVersion} ·
              {item.accessScope === "ORGANIZATION_SHARED" ? " 组织共享" : " 当前用户授权"}
            </Typography.Text>
            <Typography.Paragraph type="secondary" ellipsis={{ rows: 3, expandable: true }} style={{ marginBottom: 0, marginTop: 4 }}>
              {item.snippet}
            </Typography.Paragraph>
          </div>
        ))}
      </Space>
    </Modal>
  );
}
