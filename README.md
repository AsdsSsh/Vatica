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
| `backend/` | Spring Boot 后端（Agent 编排 + 工具层 + MCP）——迭代 9 起为**纯 API 服务**（无静态资源，OpenAPI 契约 /v3/api-docs） |
| `frontend/` | Tauri + React 桌面壳——独立工程，只通过 HTTP API 与后端交互 |

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
- CORS 已放行 `tauri://localhost` 与开发端口（1420/5173）
- 前后端分离（迭代 9）：前端 API 基地址默认 `http://localhost:8080`（桌面版后端随壳以 sidecar 启动），可在界面"服务设置"（顶栏接口图标）里修改、即时生效——云端后端换地址即切换

### 迭代 3：一句话生成文档（演示场景）

Agent 的文件工具：`read_file` / `write_file` / `list_files` / `create_word_report` / `create_excel_stats`。
一条 prompt 走全链路"列目录 → 读文件 → 生成 Word 周报 + Excel 统计"：

```bash
curl -N -X POST localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  --data-binary @payload.json    # {"message":"读取 本周工作记录.md，生成一份周报 Word 和一张统计 Excel"}
```

- Word 内容约定（`create_word_report` 的 sections 参数）：`# ` 开头=一级标题、`## ` 开头=二级标题、其余行=正文段落
- Excel 数字规则（`create_excel_stats`）：仅严格数字（无前导零/无科学计数法）写为数值单元格，其余一律文本——"001"编号不会变 1
- 迭代 11 起产物落盘在当前工作区根目录（后端启动目录），不再有 `data/`

### 迭代 3.5：PIM 私人数据（日历 / 待办 / 邮件）

Agent 现有 **17 个工具**（迭代 11 起含 `list_workspace_roots`），PIM 三件套：

| 工具 | 功能 | 存储/说明 |
|---|---|---|
| `calendar_query` / `calendar_create` / `calendar_import` | 查日程（重复自动展开）/ 建日程 / 导入 ICS | 手写 RFC5545 子集，本地 `.vatica/calendar.ics` |
| `todo_add` / `todo_list` / `todo_complete` / `todo_remind` | 待办增查改 + 到期提醒 | 本地 `.vatica/todos.json`（运行时数据，已 gitignore） |
| `mail_query` / `mail_send` | IMAP 收件箱查询/搜索 + SMTP 发送 | 环境变量配置；发送需用户确认（confirm="yes"） |

主演示场景（一句话）：

```bash
# payload：{"message":"今天是2026-08-14。先用 calendar_query 整理下周（2026-08-17 至 2026-08-21）日程，为每条日程创建准备材料的待办，最后用 todo_list 列出。"}
curl -N -X POST localhost:8080/api/chat/stream -H "Content-Type: application/json" --data-binary @payload.json
```

- 数据约束：涉及具体时间/日期的内容模型必须从工具返回值引用（幻觉控制约定，写在工具描述里）
- 日历数据存 `.vatica/calendar.ics`；旧 `data/calendar.ics` 会在启动时自动迁移到 `.vatica/`
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

- **Server**：17 个本地工具零改动经 Streamable HTTP 暴露在 `POST /mcp`（加 starter 自动转换 ToolCallback，任何 MCP 客户端可接入）
- **Client**：已接入**高德地图官方 MCP**（`https://mcp.amap.com`，迭代 4.5，替代原模拟天气服务），15 个地图/天气工具与本地 17 个工具合并供 Agent 调用

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
- 注意：`spring.ai.mcp.server.protocol: STREAMABLE` 必须显式声明（缺失退回 SSE、/mcp 返回 404）
- **懒初始化 + 韧性兜底（迭代 8）**：`spring.ai.mcp.client.initialized=false`——启动不握手第三方服务（未配 AMAP_MCP_KEY / 断网也能正常启动），首次真正用到 MCP 工具时才连接；连接失败由 `McpToolProviderGuard` 兜底（空工具集 + 5 分钟退避不重试），本地工具与对话不受影响

### 迭代 5：核心闭环（任务拆解 + 人工审批 + 状态机 + MySQL 持久化）

一句话创建任务 → Planner 拆解 → **人工审批计划** → 逐步执行 → 敏感步骤（发邮件/覆盖文件）**审批点挂起** → 批准后继续 → 交付。全链路状态落 MySQL：

```bash
# 1. 创建任务（返回任务 id + 拆解计划，状态 PENDING 待审批）
curl -X POST localhost:8080/api/task -H "Content-Type: application/json" -d "{\"goal\":\"读取 本周工作记录.md 生成周报 Word，并发送邮件通知张总\"}"

# 2. 审批计划并开始执行（同步推进到下一个审批点或终态）
curl -X POST localhost:8080/api/task/<任务id>/approve

# 3. 若命中敏感步骤审批点（status=PENDING_APPROVAL），再次 approve 继续
# 4. 查询进度 / 最近任务列表
curl localhost:8080/api/task/<任务id>
curl localhost:8080/api/task
```

- 状态机：PENDING → RUNNING → PENDING_APPROVAL → REVIEW → DONE / FAILED，低分自动返工 RETRY（限 2 次）→ 超限 NEEDS_REVISION；DONE 可人工返工重开
- **MySQL 配置**（开发模式依赖 MySQL，凭据走环境变量、不进 git；打包模式自动切 H2 文件库零依赖，见迭代 8）：
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

### 迭代 7：UI 完成（工作台全能力）

右侧任务面板 = 完整任务工作台（后端新增接口见括号）：

- **创建任务**：一句话输入 → Planner 拆解（POST /api/task）
- **步骤实时打勾**：订阅步骤级 SSE 进度事件（GET /api/task/{id}/events，订阅即回放快照），执行中/已完成/待审批三态图标
- **审批弹窗**：计划审批与敏感步骤审批自动弹出，批准/终止一键（POST /api/task/{id}/approve）
- **运行中终止**：PENDING/RUNNING/PENDING_APPROVAL 可终止 → CANCELLED 终态（POST /api/task/{id}/cancel，协作式取消 + 波次粒度生效）
- **执行准确率**：Judge score 徽标（≥70 绿 / <70 红）+ verdict + 返工次数；DONE/NEEDS_REVISION 可一键返工（POST /api/task/{id}/rework）
- **文件产物**：GET /api/files 列表（白名单目录），双击用系统默认程序打开（Tauri opener 插件）
- **模型选择器**：对话区切换 DeepSeek / 通义千问（GET /api/chat/models；备用模型 setx QWEN_API_KEY 后启用，未配置置灰）

### 迭代 8：打包交付（Tauri sidecar + NSIS 安装包）

#### 架构图（安装包形态）

```
桌面壳 Tauri 2（Rust 壳 + WebView2 跑 React 三栏工作台）
  │  前端只对 8080 说话：fetch / SSE / REST（CORS 放行 tauri://localhost；迭代 9 起基址可在"服务设置"改）
  │  release 模式：启动时拉起 sidecar、退出时 kill；开发模式不拉起（后端手动启动）
  ▼
vatica-backend-x86_64-pc-windows-msvc.exe —— Rust 启动器（externalBin sidecar）
  │  用捆绑的 jlink 最小 JRE 启动 Spring Boot fat jar（-Xmx256m）
  │  工作目录切到 %APPDATA%\Vatica（安装目录只读，数据落用户目录）
  │  注入 VATICA_WATCHDOG_PID = 自身 PID
  ▼
后端 Spring Boot 4.1（--spring.profiles.active=packaged）
  ├─ 16 个本地工具 ── MCP Server 暴露 POST /mcp（Streamable HTTP）
  ├─ MCP Client ── 高德官方 MCP（懒初始化 + 失败退避兜底）
  ├─ 持久化：H2 文件库 .vatica/vatica-db.mv.db（零依赖开箱即用；PACKAGED_DB_URL + PACKAGED_DB_USERNAME/PASSWORD 可切回 MySQL）
  └─ 看门狗 SidecarWatchdog：轮询不到启动器 PID → 10 秒内自行退出（防 8080 孤儿进程）
```

**进程生命周期**：

- 正常退出：壳收到退出事件 → kill 启动器 → 启动器 wait() 收尾
- 壳被强杀：启动器随进程树终止 → 后端看门狗 10 秒内感知并自行退出——不会留下孤儿后端占着 8080

#### 打包步骤（三件套可一键重新生成）

```powershell
cd frontend/src-tauri
.\package-sidecar.ps1              # 生成三件套：launcher → binaries/、fat jar + jlink JRE → backend-sidecar/（-SkipTests 快速出包 / -ForceJre 重建 JRE）
npm run tauri build                # NSIS 安装包 → target/release/bundle/nsis/Vatica_0.1.0_x64-setup.exe
```

- 生成物（`binaries/`、`backend-sidecar/`、`sidecar-stage/`）已 gitignore；**源码**（`launcher/`、`package-sidecar.ps1`）入库，随时可重建
- `sidecar-stage/` 是安装目录布局的镜像：直接运行其中的启动器即可本地冒烟
- **首次构建要下载 NSIS 工具链**（GitHub，国内网络可能超时——实测踩过）：预下载到 `%LOCALAPPDATA%\tauri\` 即可离线构建——
  ① `nsis-3.11.zip` 解压为 `%LOCALAPPDATA%\tauri\NSIS\`（完整目录，含 Bin/Stubs/Include/Plugins）
  ② `nsis_tauri_utils.dll` 放 `%LOCALAPPDATA%\tauri\NSIS\Plugins\x86-unicode\additional\`
  两个文件的 SHA1 校验值与 13 项必需文件清单见 tauri-bundler 源码（bundle/windows/nsis/mod.rs）

#### 安装后体验（零依赖）

安装包在**没有 MySQL / 没有 JDK** 的机器上双击即可用，数据全部落 `%APPDATA%\Vatica`。
需要联网/聊天时再配置环境变量（设置后重启应用生效）：

| 环境变量 | 用途 | 缺省影响 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 对话 / 任务执行 | 聊天不可用，界面与任务管理正常 |
| `AMAP_MCP_KEY`（可选） | 高德地图/天气 MCP 工具 | 无 MCP 远程工具，本地 16 工具照常 |
| `QWEN_API_KEY`（可选） | 备用模型（通义千问） | 模型选择器置灰 |
| `MAIL_*`（可选） | 邮件查询/发送 | mail_* 工具返回未配置指引 |

#### 演示场景脚本（桌面应用，承接演示视频职能）

1. **周报交付**：创建任务"读取 本周工作记录.md，生成周报 Word 和统计 Excel"→ 批准计划 → 右侧面板步骤实时打勾 → Judge 100 分绿徽标 → 成品在工作区根目录
2. **个人助理**："整理下周日程并提醒我准备会议材料"→ calendar_query / todo_add / todo_remind 全链路，数据全部来自工具返回
3. **质量门禁拦截幻觉**：让 Agent 做计算类总结任务 → Judge 低分红徽标 → 自动返工 2 次 → NEEDS_REVISION（error 里带评分与原因）
4. **运行中终止**：长任务执行中点击终止 → 状态收敛 CANCELLED（协作式取消，不留半成品假装成功）
5. **天气（MCP）**：配置 AMAP_MCP_KEY 后问"杭州今天天气怎么样"→ Agent 经高德 MCP 作答并注明数据来源

### 迭代 8.5：模型配置中心（图形界面管理模型）

对话区顶栏 **齿轮按钮** 打开模型设置：增删改模型槽位、测试连接、保存全部——**保存即生效，无需重启**。

- **两种协议**，覆盖主流模型生态：
  - **OpenAI 兼容**：DeepSeek / 通义千问 / Kimi / GLM / Ollama 本地端点等（换 base-url 即切换）
  - **Anthropic**：Claude 及兼容端点
- **每个槽位可配**：名称 / 标识 / base-url / API Key（本地端点可留空）/ 模型 ID / 温度 / 启用开关
- **配置存储**：`.vatica/models.json`（打包模式 `%APPDATA%\Vatica\.vatica\`）——界面配置优先；未保存过时回退 yml/环境变量（DeepSeek + 通义默认槽位，与迭代 7 行为一致）
- **默认模型 = 第一个启用的槽位**：对话、任务执行、规划、评测统一走它；模型选择器可临时切换
- **测试连接**：用当前编辑内容直接测（不必先保存），失败原因（如 401 密钥无效）直接显示
- 后端接口：`GET/PUT /api/models`、`POST /api/models/test`；模型清单 `GET /api/chat/models` 改为动态注册表驱动

### 迭代 9：前后端分离（工程彻底解耦）

后端收敛为**纯 API 服务**，前后端只通过 HTTP API 契约交互（桌面壳 Tauri 保留不变）：

- **后端零静态资源**：删除迭代 1 遗留的 `static/index.html` 验证页（使命已由 React 前端承接）；浏览器打开 8080 根路径返回 API 索引 JSON（接口前缀 + 契约入口），不再有任何页面
- **OpenAPI 契约 = 单一事实来源**：springdoc 3.1.0（面向 Spring Boot 4）——`GET /v3/api-docs` 为机器可读契约，`/swagger-ui.html` 开发期可视化（只收录 `/api/**` 业务接口，`/mcp` 属 MCP 协议端点不纳入）
- **响应类型化 DTO**：chat / task / files / models 全部接口由 `Map` 投影改为类型化 DTO（`TaskDetailDto` / `TaskSummaryDto` / `ModelInfoDto` / `FileArtifactDto`），schema 完整可读；任务详情与 SSE 事件负载同构（事件 = 详情 + `type`），前端一个类型两处复用
- **错误契约统一** `{"message": "用户可读原因"}`：400 业务校验 / 404 资源不存在（任务 id 查无、未知路径）/ 500 根因消息（异常链剥到最内层、不泄漏堆栈），全局 `@RestControllerAdvice` 一处收口；前端解析并透出服务端消息
- **前端基址可配置**：顶栏**接口图标**（服务设置）修改后端地址，保存即时生效（localStorage 覆盖 > `VITE_API_BASE` 构建期变量 > 默认 localhost:8080）——P1-6 云端后端切换零代码
- **工程边界**：后端 = `mvn spring-boot:run` 纯 API（8080）；前端 = `npm run dev`（Vite 1420）+ `npm run build` 独立构建；打包脚本三件套不变（jar 不再含静态资源）

```bash
# 契约自查（后端启动后）
curl localhost:8080/                # API 索引 JSON
curl localhost:8080/v3/api-docs     # OpenAPI 契约
curl localhost:8080/api/task/不存在   # {"message":"操作失败：任务不存在（id=不存在）。"} HTTP 404
```

### 迭代 10：质量修复（全面体检问题清零）

对迭代 1-9 做全面体检后集中修复 13 项问题（3 高 + 5 中 + 5 低），要点：

- **模型设置修复**：修复"添加模型"点击无反应（新增时未写入空槽位模板）+ 编辑表单在 Form 挂载后再回填；模型设置保存不再重置对话区已选模型
- **模型客户端缓存隔离**：`ModelRegistry` 缓存键补 `withTools` 维度——对话客户端（带工具）与规划/评测客户端（无工具）不再互相复用
- **CORS 放行 PUT**：模型配置保存（`PUT /api/models`）在 Tauri Windows origin / Vite 开发 origin 下预检 200
- **配置校验归一化**：`models.json` 保存/读取统一归一化（id 小写唯一、协议 lowercase、启用槽位 baseUrl/model/temperature 必填）
- **数据与契约加固**：PIM 工具首次写入自动建 `.vatica/` 目录；`/api/files` 下线；流式生成中禁用切换会话防串流
- **开发模式环境变量兜底**：`setx` 只写注册表、运行中的终端/IDE 拿不到新值——后端启动时自动从 `HKCU\Environment` 回填缺失的 `MYSQL_*` / `*_API_KEY` 等变量（与打包版启动器同策略），避免 `Access denied ... using password: NO` 启动失败
- 回归：mvn test **237** 全绿 + npm run build 通过

### 迭代 11：文件权限改造（前端权限中心 + 后端执行校验，删除 data/）

- **默认工作区 = 后端启动目录**（Codex workspace 语义），工作区内 `workspace-write` 自动放行，越界触发权限请求
- **前端 localStorage 是权限事实来源**：沙盒模式（read-only / workspace-write / danger-full-access）、工作区根、永久授权都存前端，聊天/任务请求自动携带权限快照
- **权限弹窗**：任务面板与对话区都会弹"文件访问需要授权"，可选"仅本次允许 / 永久允许 / 拒绝"；5 分钟超时自动拒绝
- **任务工作目录**：创建任务时可填任务工作目录（WorkBuddy 式），不填用全局工作区
- **内部状态迁到 `.vatica/`**：calendar.ics / todos.json / models.json / H2 数据库；旧 `data/` 启动时自动迁移并删除
- **新工具 `list_workspace_roots`**：Agent 可查询当前沙盒模式与工作区根
- 接口：`POST /api/permissions/requests/{id}/approve`（remember）、`POST /api/permissions/requests/{id}/deny`

### 迭代 12：前端 UI/UX 优化（视觉体系 V2 + 体验收尾）

- **视觉体系 V2**：Vatica Indigo/Paper/Ink semantic tokens + 亮/暗/跟随系统三态主题（`index.html` 首帧防闪）；顶栏呼吸状态灯（在线常亮 / 连接中琥珀 / 工作青蓝 / 离线灰点）；会话/任务列表品牌渐变选中条；消息气泡工作台卡片化；空状态品牌化；favicon 替换为 Vatica logo
- **连接状态**：`GET /` 探活 + 指数退避重试（1s→30s）；离线横幅 + 状态灯；恢复后自动刷新模型与任务列表
- **输入与流式体验**：中文输入法组合态回车守卫（聊天/任务/权限设置三处）；智能滚动不强制拽底 + 回到底部按钮；首 token 前"正在思考"，有内容后流式光标；空状态建议卡 + 消息复制
- **工具活动胶囊**：聊天 SSE 新增 `tool_activity`（start/end/failed + 工具名 + 耗时），对话区显示"正在调用工具…"
- **会话管理**：localStorage 持久化（50 会话 / 200 条 / 20k 字符上限）+ 启动恢复 + 重命名/删除（确认 + 保底新会话）
- **权限体验**：弹窗统一为共享组件、遮罩不可误关；"记住授权"在**当前任务/会话 channel 内即时生效**（后端内存级临时授权，取消/收尾自动清理）
- **任务面板**：Tauri dialog 目录选择器（任务工作目录 + 权限设置）；步骤结果可展开/复制；任务相对时间；切任务重置弹窗状态
- **设置一致性**：模型设置 dirty 确认；权限"清空授权"改草稿 + Popconfirm；服务设置校验 catch
- **性能**：Markdown 预览直连 + manualChunks 拆包（单 chunk 2066KB → 主 chunk 51.4KB）
- 回归：mvn test 237 → **241** 全绿 + npm run build + cargo check + headless Chrome（会话恢复 / IME composition 守卫 / 离线横幅）冒烟通过

### 迭代 12.5：体验热修（Markdown 规范化 + skills.sh 配色风格）

- **Markdown 规范化**：模型输出 `###标题`、`1.步骤`、`-要点`、`>引用` 等缺少空格的语法时自动补齐后再渲染，界面不再残留 `###` 符号
- **skills.sh 配色 V2.2**：暗色纯黑背景 `#000` + 面板 `#171717` + 气泡 `#1f1f1f/#292929` + 边框 `#292929`，强调色琥珀橙 `#f99c00/#ffb200`；浅色 `#fff/#fafafa/#f2f2f2/#ebebeb`；靛蓝/青蓝品牌色退役，logo/状态灯/主按钮/选中条统一琥珀橙
- 验证：`npm run build` 通过 + headless Chrome 断言（`###标题` 渲染为 `<h3>`、暗色背景/面板/气泡/文字与主按钮计算样式符合 tokens）

### 迭代 13：云端凭据体系（多客户端后端）

- **主密钥与信封加密**：`.vatica/master.key` + AES-256-GCM（每条秘密独立 DEK，主密钥包裹 DEK）
- **账号/组织/JWT**：`POST /api/auth/register|login`；PBKDF2 密码哈希 + HS256 JWT；`vatica.auth.enabled` 开关（默认关闭，云端部署置 true）
- **模型凭据**：`model_credential` / `user_model_credential` 密文表；`GET /api/models` 只回 `apiKeySet/apiKeyHint`；PUT 语义 `null=keep / 空串=clear / 非空=set`
- **自配模型槽位**：`/api/models/user-slots`，`EPHEMERAL`（key 不落库）/ `ENCRYPTED_AT_REST`（云端加密保存，任务重启可恢复）；模型选择器「内置/我的模型」双分组
- **请求级临时凭据**：聊天/任务请求可携带 `credential`，服务端仅内存构建客户端，零持久化；与 `modelId` 互斥
- **任务恢复**：`TaskRecord.modelSource/recoverable`；启动清理 EPHEMERAL→FAILED、PLATFORM→可继续；`POST /api/task/{id}/resume`
- **外部服务设置**：AMAP / 邮件 / 数据库统一加密存 `.vatica/integrations.json`，启动后处理器注入；模型/AMAP/邮件凭据全部去环境变量
- **桌面瘦客户端**：Tauri 壳不再打包/拉起 sidecar，直连后端基址（默认 localhost:8080，服务设置可切换）

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
