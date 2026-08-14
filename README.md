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

### 迭代 3.5：PIM 私人数据（日历 / 待办 / 邮件）

Agent 现有 **16 个工具**，新增 PIM 三件套：

| 工具 | 功能 | 存储/说明 |
|---|---|---|
| `calendar_query` / `calendar_create` / `calendar_import` | 查日程（重复自动展开）/ 建日程 / 导入 ICS | 手写 RFC5545 子集，本地 `data/calendar.ics` |
| `todo_add` / `todo_list` / `todo_complete` / `todo_remind` | 待办增查改 + 到期提醒 | 本地 `data/todos.json`（运行时数据，已 gitignore） |
| `mail_query` / `mail_send` | IMAP 收件箱查询/搜索 + SMTP 发送 | 环境变量配置；发送需用户确认（confirm="yes"） |

主演示场景（一句话）：

```bash
# payload：{"message":"今天是2026-08-14。先用 calendar_query 整理下周（2026-08-17 至 2026-08-21）日程，为每条日程创建准备材料的待办，最后用 todo_list 列出。"}
curl -N -X POST localhost:8080/api/chat/stream -H "Content-Type: application/json" --data-binary @payload.json
```

- 数据约束：涉及具体时间/日期的内容模型必须从工具返回值引用（幻觉控制约定，写在工具描述里）
- `data/calendar.ics` 已内置下周 4 条演示日程（含每周重复的"项目周会"）
- **邮件配置**（可选，不配也能用其他工具）：
  ```powershell
  setx MAIL_IMAP_HOST imap.qq.com
  setx MAIL_SMTP_HOST smtp.qq.com
  setx MAIL_USERNAME 你的邮箱
  setx MAIL_PASSWORD 你的授权码     # 设置后重开终端再启动
  ```
- 邮件发送是副作用操作：模型必须先征得你确认，确认后才会发（完整审批流在迭代 5 HITL）

### 迭代 4：MCP 协议能力（Server + Client）

Vatica 同时是 MCP Server 与 MCP Client：

- **Server**：16 个本地工具零改动经 Streamable HTTP 暴露在 `POST /mcp`（加 starter 自动转换 ToolCallback，任何 MCP 客户端可接入）
- **Client**：接入第三方 MCP 服务（演示为独立进程的模拟天气服务，端口 8081）

双进程启动（**先天气服务、后主应用**）：

```powershell
# 终端 1：第三方模拟天气 MCP 服务（8081，与主应用同代码、profile 隔离）
cd backend
mvn spring-boot:run -Pweather -Dspring-boot.run.profiles=weather

# 终端 2：主应用（8080，启动时经 MCP 客户端与天气服务握手）
$env:DEEPSEEK_API_KEY = ((Get-Content ..\test_key.txt -Raw) -replace '(?s)^deepseek:\s*','' -replace '\s','')
mvn spring-boot:run
```

验证（Agent 经 MCP 调用远程工具）：

```bash
curl -N -X POST localhost:8080/api/chat/stream -H "Content-Type: application/json" \
  --data-binary @payload.json    # {"message":"杭州今天天气怎么样？未来3天呢？"}
```

- 天气数据为确定性模拟值（协议互操作真实、外部依赖为零），工具结果标注数据来源，模型如实转述
- 接真实第三方 MCP 服务 = 改 yml 一个 url（`spring.ai.mcp.client.streamable-http.connections.*`）
- 注意：`spring.ai.mcp.server.protocol: STREAMABLE` 必须显式声明（缺失退回 SSE、/mcp 返回 404）；
  主应用启动依赖天气服务在线，暂不演示时注释掉 yml 里 connections.weather 块

### 迭代 5：核心闭环（任务拆解 + 人工审批 + 状态机 + MySQL 持久化）

一句话创建任务 → Planner 拆解 → **人工审批计划** → 逐步执行 → 敏感步骤（发邮件/覆盖文件）**审批点挂起** → 批准后继续 → 交付。全链路状态落 MySQL：

```bash
# 1. 创建任务（返回任务 id + 拆解计划，状态 PENDING 待审批）
curl -X POST localhost:8080/api/task -H "Content-Type: application/json" -d "{\"goal\":\"读取 data/本周工作记录.md 生成周报 Word，并发送邮件通知张总\"}"

# 2. 审批计划并开始执行（同步推进到下一个审批点或终态）
curl -X POST localhost:8080/api/task/<任务id>/approve

# 3. 若命中敏感步骤审批点（status=PENDING_APPROVAL），再次 approve 继续
# 4. 查询进度 / 最近任务列表
curl localhost:8080/api/task/<任务id>
curl localhost:8080/api/task
```

- 状态机：PENDING → RUNNING → PENDING_APPROVAL → REVIEW → DONE / FAILED（RETRY/NEEDS_REVISION 为 5.5 质量闭环预留）
- **MySQL 配置**（迭代 5 起后端启动依赖 MySQL）：
  ```powershell
  # 建库建用户（mysql -u root -p 里执行）：
  #   CREATE DATABASE vatica CHARACTER SET utf8mb4;
  #   CREATE USER 'vatica'@'localhost' IDENTIFIED BY '<密码>';
  #   GRANT ALL PRIVILEGES ON vatica.* TO 'vatica'@'localhost';
  setx MYSQL_USERNAME vatica
  setx MYSQL_PASSWORD <密码>    # 设置后重开终端
  ```
  表结构由 JPA 自动创建（ddl-auto: update）；测试环境用 H2（MySQL 兼容模式），单测零外部依赖
- 会话记忆已持久化：多轮对话重启后仍可引用前文（内存滑窗热缓存 + MySQL 落库）

### 迭代 2.5 新增配置（application.yml，均可调）

| 配置 | 默认 | 说明 |
|---|---|---|
| `vatica.chat.sse.timeout` | 5m | SSE 超时主动收尾（上游挂起/客户端假死时防连接泄漏） |
| `vatica.chat.memory.max-messages` | 20 | 单会话历史滑动窗口上限（消息数） |
| `vatica.chat.memory.max-chars` | 16000 | 单会话历史字符数上限（token 的工程近似） |
| `vatica.chat.memory.max-sessions` | 64 | 会话总数上限（LRU 淘汰） |
| `vatica.tool.max-calls-per-request` | 20 | 单次请求工具调用次数护栏（防死循环烧 token） |
