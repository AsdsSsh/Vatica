# Vatica — AI 个人助理 + 办公 Agent 平台

对标腾讯 WorkBuddy / 字节 TRAE Work / 阿里千问办公的**个人 AI 助理**桌面应用：定位首先偏向个人助理——接入**日历 / 邮件 / 待办**（PIM）管理个人事务，叠加办公执行能力（文档交付）

**核心闭环**：一句话 → 任务拆解 → 多 Agent 调用工具（文件 / 文档 / PIM / MCP）→ 人工审批 → 交付（日程管理 / 邮件 / Word/Excel 成品）

**质量闭环**：LLM-as-Judge 评测每个任务执行结果（执行准确率可量化）→ 低分自动返工（限 2 次）→ 超限交人工，用户可手动返工

**主演示场景**："帮我整理下周的日程并提醒我准备会议材料"——Agent 查询日历、生成待办、设置提醒，具体数据全部取自工具返回

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Spring Boot 4.1 + Spring AI 2.0 + MCP Java SDK + Apache POI + JavaMail（Java 21） |
| 前端 | Tauri 2 + React 19（桌面应用） |
| 模型 | DeepSeek（OpenAI 兼容 API，可换通义千问） |
| 测试 | JUnit 5 + AssertJ（工具层/状态机单测）+ GreenMail（邮件集成测试） |

## 目录结构

| 目录 | 说明 |
|---|---|
| `backend/` | Spring Boot 后端（Agent 编排 + 工具层 + MCP） |
| `frontend/` | Tauri + React 桌面壳 |

## 项目文档

- `AI办公Agent平台-项目规划.md` — 规划与参考资料
- `任务总清单.md` — 全部任务一览（规划用）
- `任务进度.md` — 迭代进度、验收标准、风险记录

## 快速开始（后端）

1. 设置环境变量 `DEEPSEEK_API_KEY`（DeepSeek 开放平台申请；本项目从 `test_key.txt` 注入，见下方 PowerShell 示例）
2. `cd backend && mvn spring-boot:run`
3. 验证流式对话：
   `curl -N -X POST localhost:8080/api/chat/stream -H "Content-Type: application/json" -d "{\"message\":\"你好\"}"`

### 常见坑（迭代 2.5 归档）

- **8080 端口被占用**（迭代 1 踩过）——先查后杀：
  ```powershell
  netstat -ano | findstr :8080          # 找到 PID
  taskkill /PID <PID> /F                # 杀掉占用进程
  ```
- **中文 payload 编码损坏**：Git Bash 里 `curl -d` 传中文会损坏（400 invalid unicode），统一用 `--data-binary @文件`（文件存 UTF-8）：
  ```bash
  curl -N -X POST localhost:8080/api/chat/stream \
    -H "Content-Type: application/json" \
    --data-binary @payload.json          # payload.json 内容如 {"message":"你好"}
  ```
- **多轮对话**：请求体可带可选 `sessionId`，同一 id 自动携带前文（内存版短期记忆，重启即清）：
  ```json
  {"message": "我叫什么名字？", "sessionId": "s1"}
  ```

### 迭代 3：一句话生成文档（演示场景）

Agent 共 7 个工具：`read_file` / `write_file` / `list_files` / `calculator` / `text_stats` / `create_word_report` / `create_excel_stats`。
一条 prompt 走全链路"列目录 → 读文件 → 生成 Word 周报 + Excel 统计"：

```bash
curl -N -X POST localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  --data-binary @payload.json    # {"message":"读取 data/本周工作记录.md，生成一份周报 Word 和一张统计 Excel"}
```

- Word 内容约定（`create_word_report` 的 sections 参数）：`# ` 开头=一级标题、`## ` 开头=二级标题、其余行=正文段落
- Excel 数字规则（`create_excel_stats`）：仅严格数字（无前导零/无科学计数法）写为数值单元格，其余一律文本——"001"编号不会变 1
- 产物落盘在 `backend/data/`（文件工具白名单目录，文档工具复用同一安全边界）

### 迭代 2.5 新增配置（application.yml，均可调）

| 配置 | 默认 | 说明 |
|---|---|---|
| `vatica.chat.sse.timeout` | 5m | SSE 超时主动收尾（上游挂起/客户端假死时防连接泄漏） |
| `vatica.chat.memory.max-messages` | 20 | 单会话历史滑动窗口上限（消息数） |
| `vatica.chat.memory.max-chars` | 16000 | 单会话历史字符数上限（token 的工程近似） |
| `vatica.chat.memory.max-sessions` | 64 | 会话总数上限（LRU 淘汰） |
| `vatica.tool.max-calls-per-request` | 20 | 单次请求工具调用次数护栏（防死循环烧 token） |
