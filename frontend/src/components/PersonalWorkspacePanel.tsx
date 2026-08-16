import { useEffect, useState } from "react";
import {
  App,
  Button,
  Checkbox,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
} from "antd";
import {
  CheckOutlined,
  CloudDownloadOutlined,
  DeleteOutlined,
  ExportOutlined,
  ImportOutlined,
  PlusOutlined,
  UploadOutlined,
} from "@ant-design/icons";
import {
  addCalendarEvent,
  addTodo,
  completeTodo,
  deleteCalendarEvent,
  deleteTodo,
  deleteWorkspaceFile,
  downloadWorkspaceFile,
  exportCalendar,
  fetchCalendarEvents,
  fetchTodos,
  fetchUserMailSettings,
  fetchWorkspaceFiles,
  getEphemeralMailCredential,
  importCalendar,
  saveUserMailSettings,
  testUserMailSettings,
  uploadWorkspaceFile,
  type CalendarEventView,
  type TodoView,
  type UserMailSettingsView,
  type WorkspaceEntry,
} from "../api";

interface Props { open: boolean; onClose: () => void }

type MailForm = {
  credentialMode: UserMailSettingsView["credentialMode"];
  imapHost: string; imapPort: number; smtpHost: string; smtpPort: number;
  username: string; password?: string;
};

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

/** 迭代 14：云文件、待办、日历与个人邮箱的统一工作台。 */
export default function PersonalWorkspacePanel({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [files, setFiles] = useState<WorkspaceEntry[]>([]);
  const [todos, setTodos] = useState<TodoView[]>([]);
  const [events, setEvents] = useState<CalendarEventView[]>([]);
  const [busy, setBusy] = useState(false);
  const [todoTitle, setTodoTitle] = useState("");
  const [todoDue, setTodoDue] = useState("");
  const [eventSummary, setEventSummary] = useState("");
  const [eventStart, setEventStart] = useState("");
  const [eventEnd, setEventEnd] = useState("");
  const [mailMode, setMailMode] = useState<UserMailSettingsView["credentialMode"]>("EPHEMERAL");
  const [mailForm] = Form.useForm<MailForm>();

  async function refresh() {
    setBusy(true);
    try {
      const [nextFiles, nextTodos, nextEvents, mail] = await Promise.all([
        fetchWorkspaceFiles(), fetchTodos(), fetchCalendarEvents(), fetchUserMailSettings(),
      ]);
      setFiles(nextFiles);
      setTodos(nextTodos);
      setEvents(nextEvents);
      setMailMode(mail.credentialMode);
      const local = getEphemeralMailCredential();
      mailForm.setFieldsValue({
        credentialMode: mail.credentialMode,
        imapHost: mail.imapHost || local?.imapHost || "",
        imapPort: mail.imapPort || local?.imapPort || 993,
        smtpHost: mail.smtpHost || local?.smtpHost || "",
        smtpPort: mail.smtpPort || local?.smtpPort || 465,
        username: mail.username || local?.username || "",
        password: local?.password,
      });
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => { if (open) void refresh(); }, [open]);

  async function addTodoItem() {
    if (!todoTitle.trim()) return;
    try {
      setTodos(await addTodo(todoTitle.trim(), todoDue));
      setTodoTitle(""); setTodoDue("");
    } catch (e) { message.error((e as Error).message); }
  }

  async function addEventItem() {
    if (!eventSummary.trim() || !eventStart) return;
    try {
      setEvents(await addCalendarEvent({
        summary: eventSummary.trim(), start: eventStart, end: eventEnd, rrule: null,
      }));
      setEventSummary(""); setEventStart(""); setEventEnd("");
    } catch (e) { message.error((e as Error).message); }
  }

  async function saveMail() {
    try {
      const values = await mailForm.validateFields();
      await saveUserMailSettings({ ...values, password: values.password ?? null });
      message.success(values.credentialMode === "EPHEMERAL" ? "邮箱已保存，密码仅保留在本机" : "邮箱已加密保存");
      mailForm.setFieldValue("password", undefined);
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    }
  }

  async function testMail() {
    try {
      const values = await mailForm.validateFields();
      const password = values.password || getEphemeralMailCredential()?.password || "";
      const result = await testUserMailSettings(values.credentialMode === "EPHEMERAL" ? {
        imapHost: values.imapHost, imapPort: values.imapPort, smtpHost: values.smtpHost,
        smtpPort: values.smtpPort, username: values.username, password,
      } : undefined);
      message.success(result.message);
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    }
  }

  return (
    <Modal title="个人工作台" open={open} onCancel={onClose} footer={null} width={900} destroyOnHidden>
      <Tabs items={[
        {
          key: "files", label: "云文件", children: (
            <Flex vertical gap={10}>
              <Flex justify="space-between" align="center">
                <Typography.Text type="secondary">当前账号的隔离工作区</Typography.Text>
                <Upload showUploadList={false} beforeUpload={(file) => {
                  void uploadWorkspaceFile(file).then(() => refresh()).catch((e: Error) => message.error(e.message));
                  return false;
                }}><Button icon={<UploadOutlined />}>上传</Button></Upload>
              </Flex>
              <Table<WorkspaceEntry> loading={busy} rowKey="path" size="small" pagination={false} dataSource={files}
                columns={[
                  { title: "名称", dataIndex: "path", ellipsis: true },
                  { title: "大小", dataIndex: "size", width: 100, render: (v: number, r) => r.directory ? "目录" : `${v} B` },
                  { title: "修改时间", dataIndex: "modifiedAt", width: 180, render: (v: string) => new Date(v).toLocaleString() },
                  { title: "", width: 90, render: (_, row) => <Space>
                    {!row.directory && <Button type="text" aria-label="下载" icon={<CloudDownloadOutlined />} onClick={() => {
                      void downloadWorkspaceFile(row.path).then((blob) => saveBlob(blob, row.path.split("/").pop() || "download"));
                    }} />}
                    <Popconfirm title="删除这个项目？" onConfirm={() => void deleteWorkspaceFile(row.path).then(refresh)}>
                      <Button type="text" danger aria-label="删除" icon={<DeleteOutlined />} />
                    </Popconfirm>
                  </Space> },
                ]} />
            </Flex>
          ),
        },
        {
          key: "todos", label: "待办", children: (
            <Flex vertical gap={10}>
              <Flex gap={8}>
                <Input value={todoTitle} onChange={(e) => setTodoTitle(e.target.value)} placeholder="待办标题" />
                <Input type="date" value={todoDue} onChange={(e) => setTodoDue(e.target.value)} style={{ width: 160 }} />
                <Button type="primary" icon={<PlusOutlined />} onClick={() => void addTodoItem()}>添加</Button>
              </Flex>
              <Table<TodoView> rowKey="id" size="small" pagination={false} dataSource={todos} columns={[
                { title: "状态", width: 72, render: (_, row) => row.done ? <Tag color="success">完成</Tag> : <Tag>待办</Tag> },
                { title: "标题", dataIndex: "title" },
                { title: "截止", dataIndex: "due", width: 120, render: (v: string | null) => v || "-" },
                { title: "", width: 90, render: (_, row) => <Space>
                  {!row.done && <Button type="text" aria-label="完成" icon={<CheckOutlined />} onClick={() => void completeTodo(row.id).then(setTodos)} />}
                  <Popconfirm title="删除这条待办？" onConfirm={() => void deleteTodo(row.id).then(() => fetchTodos().then(setTodos))}>
                    <Button type="text" danger aria-label="删除" icon={<DeleteOutlined />} />
                  </Popconfirm>
                </Space> },
              ]} />
            </Flex>
          ),
        },
        {
          key: "calendar", label: "日历", children: (
            <Flex vertical gap={10}>
              <Flex gap={8} wrap>
                <Input value={eventSummary} onChange={(e) => setEventSummary(e.target.value)} placeholder="日程标题" style={{ flex: 1, minWidth: 180 }} />
                <Input type="datetime-local" value={eventStart} onChange={(e) => setEventStart(e.target.value)} style={{ width: 190 }} />
                <Input type="datetime-local" value={eventEnd} onChange={(e) => setEventEnd(e.target.value)} style={{ width: 190 }} />
                <Button type="primary" icon={<PlusOutlined />} onClick={() => void addEventItem()}>添加</Button>
              </Flex>
              <Flex justify="flex-end" gap={8}>
                <Upload accept=".ics,text/calendar" showUploadList={false} beforeUpload={(file) => {
                  void importCalendar(file).then(setEvents).catch((e: Error) => message.error(e.message)); return false;
                }}><Button icon={<ImportOutlined />}>导入 ICS</Button></Upload>
                <Button icon={<ExportOutlined />} onClick={() => void exportCalendar().then((blob) => saveBlob(blob, "vatica-calendar.ics"))}>导出 ICS</Button>
              </Flex>
              <Table<CalendarEventView> rowKey="id" size="small" pagination={false} dataSource={events} columns={[
                { title: "标题", dataIndex: "summary" },
                { title: "开始", dataIndex: "start", width: 170, render: (v: string) => v.replace("T", " ") },
                { title: "结束", dataIndex: "end", width: 170, render: (v: string) => v.replace("T", " ") },
                { title: "", width: 48, render: (_, row) => <Popconfirm title="删除这条日程？" onConfirm={() => void deleteCalendarEvent(row.id).then(() => fetchCalendarEvents().then(setEvents))}>
                  <Button type="text" danger aria-label="删除" icon={<DeleteOutlined />} />
                </Popconfirm> },
              ]} />
            </Flex>
          ),
        },
        {
          key: "mail", label: "我的邮箱", children: (
            <Form<MailForm> form={mailForm} layout="vertical" onValuesChange={(changed) => {
              if (changed.credentialMode) setMailMode(changed.credentialMode);
            }}>
              <Form.Item name="credentialMode" label="密码保存方式" initialValue="EPHEMERAL">
                <Segmented options={[
                  { label: "仅本机", value: "EPHEMERAL" },
                  { label: "云端加密", value: "ENCRYPTED_AT_REST" },
                ]} />
              </Form.Item>
              <Flex gap={10}><Form.Item name="imapHost" label="IMAP 主机" rules={[{ required: true }]} style={{ flex: 1 }}><Input /></Form.Item>
                <Form.Item name="imapPort" label="端口" rules={[{ required: true }]}><InputNumber min={1} max={65535} /></Form.Item></Flex>
              <Flex gap={10}><Form.Item name="smtpHost" label="SMTP 主机" rules={[{ required: true }]} style={{ flex: 1 }}><Input /></Form.Item>
                <Form.Item name="smtpPort" label="端口" rules={[{ required: true }]}><InputNumber min={1} max={65535} /></Form.Item></Flex>
              <Form.Item name="username" label="邮箱账号" rules={[{ required: true }]}><Input autoComplete="username" /></Form.Item>
              <Form.Item name="password" label={mailMode === "EPHEMERAL" ? "密码 / 授权码（只保存在本机）" : "密码 / 授权码（留空保持原值）"}>
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Checkbox checked disabled>邮件发送仍需在任务审批点确认</Checkbox>
              <Flex justify="flex-end" gap={8} style={{ marginTop: 16 }}>
                <Button onClick={() => void testMail()}>测试连接</Button>
                <Button type="primary" onClick={() => void saveMail()}>保存邮箱</Button>
              </Flex>
            </Form>
          ),
        },
      ]} />
    </Modal>
  );
}
