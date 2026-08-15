# Vatica — AI 个人助理 + 办公 Agent 平台

对标腾讯 WorkBuddy / 字节 TRAE Work / 阿里千问办公的**个人 AI 助理**桌面应用：定位首先偏向个人助理——接入**日历 / 邮件 / 待办**（PIM）管理个人事务，叠加办公执行能力（文档交付）

**核心闭环**：一句话 → 任务拆解 → 多 Agent 调用工具（文件 / 文档 / PIM / MCP）→ 人工审批 → 交付（日程管理 / 邮件 / Word/Excel 成品）

**质量闭环**：LLM-as-Judge 评测每个任务执行结果（执行准确率可量化）→ 低分自动返工（限 2 次）→ 超限交人工，用户可手动返工

**主演示场景**："帮我整理下周的日程并提醒我准备会议材料"——Agent 查询日历、生成待办、设置提醒，具体数据全部取自工具返回

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Spring Boot 4.1 + Spring AI 2.0 + MCP Java SDK + Apache POI + JavaMail（Java 21，虚拟线程并行） |
| 前端 | Tauri 2 + React 19 + Ant Design 6 + @uiw/react-md-editor（桌面应用） |
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

### 快速开始（前端，迭代 6 起）

```powershell
cd frontend
npm install
npm run tauri dev        # 桌面窗口（先按上方步骤启动后端）
# 或只用浏览器调试三栏 UI：
npm run dev              # http://localhost:1420
```

- 三栏布局：左会话列表 | 中对话区（SSE 流式 Markdown 打字机 + 停止按钮） | 右任务面板（最近任务与状态）
- CORS 已放行 `tauri://localhost` 与开发端口（1420/5173）；前端 API 基地址写死 `http://localhost:8080`（sidecar 模式）

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
- **Client**：已接入**高德地图官方 MCP**（`https://mcp.amap.com`，迭代 4.5，替代原模拟天气服务），15 个地图/天气工具与本地 16 个工具合并供 Agent 调用

#### 高德 MCP 接入（迭代 4.5）

```powershell
setx AMAP_MCP_KEY <高德Web服务Key>   # console.amap.com 创建"Web服务"类型 Key，重启终端后生效
```

- 工具：`maps_weather`（天气/预报）、`maps_text_search` / `maps_around_search`（地点搜索）、`maps_geo` / `maps_regeocode`（地理编码）、驾车/步行/骑行/公交路径规划、距离测量、IP 定位等 15 个
- 实测要点（curl + 全链路验证归档）：
  - 端点参数名是 **`?key=`**（官方文档写 `api_key`，但网关只认 `key`，用错返回 INVALID_USER_KEY）
  - 网关只接受 POST（GET 405），SDK 客户端自动降级为请求-响应模式；服务端无会话头（无状态）
  - 协议协商 2025-03-26（SDK 自动处理）
- 验证（Agent 经 MCP 调用真实天气数据）：

```bash
curl -N -X POST localhost:8080/api/chat/stream -H "Content-Type: application/json" \
  --data-binary @payload.json    # {"message":"杭州今天天气怎么样？请用 maps_weather 查询后告诉我。"}
```

- 接其他第三方 MCP 服务 = 在 `application.yml` 的 `spring.ai.mcp.client.streamable-http.connections` 下增加连接块（url + endpoint），无需改代码
- 注意：`spring.ai.mcp.server.protocol: STREAMABLE` 必须显式声明（缺失退回 SSE、/mcp 返回 404）；
  若配置了连接块，主应用启动时会连该服务——服务不在线时先注释掉对应连接块

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

- 状态机：PENDING → RUNNING → PENDING_APPROVAL → REVIEW → DONE / FAILED，低分自动返工 RETRY（限 2 次）→ 超限 NEEDS_REVISION；DONE 可人工返工重开
- **MySQL 配置**（迭代 5 起后端启动依赖 MySQL，凭据走环境变量、不进 git）：
  ```powershell
  # 本机 MySQL（默认）：建库建用户后设置
  setx MYSQL_USERNAME vatica
  setx MYSQL_PASSWORD <密码>
  # 云 MySQL（演示环境）：额外设置主机，重启终端后生效
  setx MYSQL_HOST REDACTED_DB_HOST
  ```
  表结构由 JPA 自动创建（ddl-auto: update）；测试环境用 H2（MySQL 兼容模式），单测零外部依赖
- 会话记忆已持久化：多轮对话重启后仍可引用前文（内存滑窗热缓存 + MySQL 落库）

### 迭代 5.5：质量闭环（LLM-as-Judge 评分 + 自动/人工返工）

任务执行完进入 REVIEW 段：Judge Agent 按评分卡打分（完整性 30 + 正确性 50 + 格式 20 = 100），
**分数 ≥ 阈值（默认 70）交付 DONE**；低分**自动返工**（限 2 次，防死循环）；超限 **NEEDS_REVISION 交人工**。
评测结果（score / verdict / reworkCount）落库并在任务详情接口返回：

```bash
# 低分任务自动返工 2 次后仍未达标 → NEEDS_REVISION（error 里带评分与原因）
curl -X POST localhost:8080/api/task/<任务id>/approve

# 人工返工：DONE（想重做）或 NEEDS_REVISION 均可，重跑并重新评测
curl -X POST localhost:8080/api/task/<任务id>/rework

# 任务详情含执行准确率
curl localhost:8080/api/task/<任务id>   # → {"score":100,"verdict":"PASS","reworkCount":0, ...}
```

- 规则校验先行：步骤无结果 → 直接 FAIL 0 分（不烧 token）；LLM 评分不可解析 → 规则兜底 PASS（score=null 如实标注）
- **返工可重入设计**：返工清空审批标记，副作用步骤（发邮件/覆盖文件）重新挂起审批——由人工确认是否重放副作用
- 配置（`vatica.judge.*`，均可调）：`pass-threshold` 默认 70；`max-auto-rework` 默认 2
- 实测：周报任务 Judge 100 分交付；计算任务执行器编造订单数据（真实幻觉）被 Judge 连续 3 轮抓住（20/40/25 分）并拒绝交付——质量门禁如实拦截

### 迭代 6：多 Agent 并行执行（后端）

Planner 声明步骤依赖 → 拓扑分层 → **同层步骤并行执行**（虚拟线程 + CompletableFuture），审批点作为屏障独占一波：

```json
// Planner 输出的计划 JSON（dependsOn：[]=与步骤 1 并行；省略=依赖上一步；只允许引用编号更小的步骤）
{"steps":[
  {"description":"list_files 扫描 data 目录","needsApproval":false,"dependsOn":[]},
  {"description":"read_file 读取周记录","needsApproval":false,"dependsOn":[]},
  {"description":"生成周报 Word（覆盖需审批）","needsApproval":true,"dependsOn":[1,2]}
]}
```

- 波次调度（WaveScheduler）：层级 = 1 + max(依赖层级)；同层一波并行、依赖链串行；未批准审批步骤独占一波（并行不绕过 HITL）
- 老计划兼容：无 dependsOn 字段（迭代 5/5.5 落库）按顺序执行，行为不变
- 虚拟线程执行器 Bean：`Executors.newVirtualThreadPerTaskExecutor()`，每步骤一虚拟线程

### 迭代 2.5 新增配置（application.yml，均可调）

| 配置 | 默认 | 说明 |
|---|---|---|
| `vatica.chat.sse.timeout` | 5m | SSE 超时主动收尾（上游挂起/客户端假死时防连接泄漏） |
| `vatica.chat.memory.max-messages` | 20 | 单会话历史滑动窗口上限（消息数） |
| `vatica.chat.memory.max-chars` | 16000 | 单会话历史字符数上限（token 的工程近似） |
| `vatica.chat.memory.max-sessions` | 64 | 会话总数上限（LRU 淘汰） |
| `vatica.tool.max-calls-per-request` | 20 | 单次请求工具调用次数护栏（防死循环烧 token） |
| `vatica.judge.pass-threshold` | 70 | Judge 评分 ≥ 阈值判 PASS 交付（迭代 5.5） |
| `vatica.judge.max-auto-rework` | 2 | 低分自动返工上限，超限 NEEDS_REVISION 交人工（迭代 5.5） |
