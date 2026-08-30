# Vatica

**把个人办公任务从自然语言指令推进到可审计结果的 AI 助理 + 办公 Agent 桌面平台。**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)
![React](https://img.shields.io/badge/React-19-61DAFB)
![Tauri](https://img.shields.io/badge/Tauri-2-FFC131)
![Tests](https://img.shields.io/badge/tests-523%20passing-brightgreen)

Vatica 不是通用聊天壳，也不复刻编程 Agent。它聚焦个人办公的高频闭环：读取日程与已授权资料 → 形成可核对的证据化草案 → 展示即将发生的副作用 → 用户批准后交付文档、待办或邮件草稿。

```text
选择日历会议
  -> 读取日历事实与可选授权资料
  -> 展示来源、建议议程、待确认项、文档和待办差异
  -> 用户批准或拒绝
  -> 写入 Markdown 准备文档和待办，可回看产物
```

核心设计立场：**模型负责理解、规划和生成建议；Vatica 负责业务事实、身份与租户隔离、权限、审批、状态转换、持久化和审计。** 模型输出永远不会被直接当作可执行的命令。

## 功能特性

### 办公闭环

- **会议准备**——用户显式选择会议；标题与时间在创建草案时快照保存，不按相似度自动合并
- **证据化草案**——日历事实、用户目标、建议、待确认事项、授权资料引用与待办差异分开呈现；知识检索不可用时显式降级，不编造结论
- **受控写入**——文档和待办批准前只有预览；批准后幂等写入，重复批准不会重复创建；拒绝不产生任何副作用
- **个人办公工具**——受权限与租户边界约束的工作区文件、日历、待办、邮件、Word/Excel 能力
- **任务工作台**——自然语言任务、计划审批、步骤进度、取消/返工、Judge 评分与交付物入口

### Agent 运行与治理

- **AgentScope 唯一运行时**——模型调用、原生工具循环、Planner/Judge 建议与 MCP Client 统一收口
- **分层上下文**——模型感知预算（固定输入/工具 Schema/历史/输出预留四本账）、滑窗 + 异步摘要 + 按需回源取证、模式路由（NORMAL / LONG_TASK / DEEP_REVIEW）
- **可信事实**——结构化关键事实带信任等级与验证状态；Agent 推断一律 `AGENT_DERIVED`，用户确认前不进入上下文
- **工具语义发现**——权限硬过滤后按词法 + Embedding 召回候选，完整 Schema 装箱，请求级动态 ToolGroup
- **人工介入（HITL）**——计划与高风险写入必须审批；重规划改变已批准副作用范围时强制重新确认
- **质量门禁**——Judge 按阈值评估；低分任务在预算内自动返工，超限交人工
- **可观测性**——`agent_span` 记录任务/规划/Wave/Agent/模型/工具/HITL/Judge 的脱敏链路摘要

### 身份、数据与安全边界

- 本地学习模式（单用户免登录）与 JWT 多用户模式可切换；云端部署启用鉴权
- 任务、会话、日历、待办、工作区、知识库与观测数据全部按 用户/组织 双键隔离
- 工作区仅接受相对路径，拒绝目录穿越与符号链接逃逸
- 模型/邮件/集成凭据不写入 Git；支持请求级临时凭据与加密持久化
- 不保存原始思维链、完整 Prompt 或密钥，只保留可诊断的脱敏摘要

## 架构

```text
Tauri 2 Desktop Shell
  React 19 + Ant Design 6
  - 对话、个人工作台、会议准备、Agent 可观测性
  - REST / SSE；服务地址可在设置页配置
                 |
                 v
Spring Boot 4.1 API (Java 21, 虚拟线程)
  - 身份、租户、权限、状态机、审计、OpenAPI 契约
  - 任务：能力匹配 -> Planner -> 并行 Wave -> HITL -> Judge
  - 上下文：预算账本 -> 滑窗/摘要/回源 -> 分层注入
                 |
        +--------+---------+----------------+
        |                  |                |
        v                  v                v
  PostgreSQL          本地办公工具       MCP 服务
  业务事实源          文件/日历/待办      高德等远程能力
  H2 用于测试         邮件/Word/Excel     失败时受控降级
```

后端是纯 API 服务，`GET /v3/api-docs` 是前后端契约的单一事实来源；桌面前端只通过 HTTP API 交互。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 4.1、Java 21、虚拟线程、Spring Data JPA、springdoc |
| Agent | AgentScope Java 2.0.2、官方 MCP Java SDK 0.17 |
| 数据 | PostgreSQL（开发/云端主库）；H2（单测与零依赖模式） |
| 前端 | Tauri 2、React 19、TypeScript、Vite、Ant Design 6 |
| 办公能力 | Apache POI、Jakarta Mail、ICS 日历、工作区文件服务 |
| 验证 | JUnit 5、AssertJ、GreenMail、tsc + Vite 构建 |

> `pgvector` 是语义知识检索的可选前置，不是启动或办公闭环的必需条件；未就绪时知识检索显式降级，其余功能不受影响。

## 快速开始

### 前置条件

- Java 21
- Node.js 当前 LTS + npm
- PostgreSQL 16+（本地或云端；跑测试不需要）
- Windows 打包另需 Rust stable、Visual Studio C++ Build Tools、WebView2 Runtime

### 1. 启动数据库与后端

凭据走环境变量，不要写入仓库。PowerShell 示例：

```powershell
$env:POSTGRES_HOST = "127.0.0.1"
$env:POSTGRES_PORT = "5432"
$env:POSTGRES_DATABASE = "vatica"
$env:POSTGRES_USERNAME = "vatica"
$env:POSTGRES_PASSWORD = "<数据库密码>"

# 本地学习模式保持 false；云端多用户部署必须设 true
$env:VATICA_AUTH_ENABLED = "false"

# 未安装 pgvector 时可显式关闭知识检索，其余流程照常
$env:VATICA_KNOWLEDGE_ENABLED = "false"

cd backend
mvn spring-boot:run
```

本地 PostgreSQL 容器（可选）：

```powershell
$env:POSTGRES_PASSWORD = "vatica-local"
docker compose -f docker-compose.postgres.yml up -d
```

启动后可访问：

| 地址 | 用途 |
| --- | --- |
| `http://localhost:8080/` | API 索引 |
| `http://localhost:8080/v3/api-docs` | OpenAPI JSON 契约 |
| `http://localhost:8080/swagger-ui.html` | Swagger UI |

### 2. 启动桌面前端

```powershell
cd frontend
npm ci
npm run tauri dev
```

仅调试 Web UI：`npm run dev`（http://localhost:1420）。

在桌面端"设置"中配置后端地址与模型。模型支持 OpenAI 兼容端点（DeepSeek、通义兼容接口等）与 Anthropic 协议；密钥不要提交到 Git。

> Windows 上 `npm ci` 若报 `EPERM` 且指向 `esbuild.exe`：先关闭占用该文件的 Vite/Tauri 进程或编辑器，再重试。

### 3. 体验会议准备

1. 在个人工作台创建或导入日程
2. 打开右侧任务面板的"会议准备"
3. 选择日期范围与一场明确的会议，可补充准备目标和授权资料选项
4. 检查来源、建议议程、待确认事项、待办差异与文档预览
5. 批准后查看创建的待办与下载入口；拒绝则不产生任何写入

## 配置参考

所有配置集中在 [backend/src/main/resources/application.yml](./backend/src/main/resources/application.yml)，环境变量优先于 yml 默认值。

### 数据库（环境变量）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `POSTGRES_HOST` | `localhost` | PostgreSQL 主机 |
| `POSTGRES_PORT` | `5432` | 端口 |
| `POSTGRES_DATABASE` | `vatica` | 数据库名 |
| `POSTGRES_USERNAME` | `vatica` | 用户名 |
| `POSTGRES_PASSWORD` | — | 密码（必填，勿入库） |

### 鉴权与网络

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `VATICA_AUTH_ENABLED` | `false` | `false` 本地学习模式（免登录）；生产多用户设 `true`（JWT，密钥由 `.vatica/master.key` 派生） |
| `vatica.auth.token-ttl` | `12h` | JWT 有效期 |
| `VATICA_CORS_ALLOWED_ORIGINS` | 空 | 额外允许的来源，逗号分隔；桌面壳与默认 Vite 来源始终放行 |

### 上下文与记忆

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `VATICA_AGENT_CONTEXT_ENABLED` | `true` | 模型窗口预算账本总开关；关闭回退固定窗口 |
| `VATICA_AGENT_OUTPUT_RESERVE_TOKENS` | `2048` | 输出预留（不参与历史分配） |
| `VATICA_AGENT_CONTEXT_SAFETY_TOKENS` | `512` | 安全余量 |
| `VATICA_AGENT_FALLBACK_CONTEXT_TOKENS` | `16000` | 未知模型的回退窗口 |
| `VATICA_CHAT_LONG_CONTEXT_MAX_MESSAGES` | `512` | 大窗口模式回源读取原文的硬行数上限 |
| `VATICA_CONVERSATION_EVIDENCE_ENABLED` | `true` | 近期窗口之前的历史按需取证 |
| `VATICA_CONVERSATION_EVIDENCE_MAX_SEARCH_MESSAGES` | `5000` | 取证候选扫描上限 |
| `vatica.chat.memory.max-messages` | `20` | 内存滑窗消息数 |
| `vatica.chat.memory.max-sessions` | `64` | 热缓存会话数（LRU） |
| `vatica.chat.memory.max-chars` | `16000` | 单会话热缓存字符上限 |
| `vatica.chat.summary.max-batch-messages` | `20` | 异步摘要批大小 |
| `vatica.chat.summary.max-auto-retries` | `2` | 摘要失败自动重试上限（指数退避，初始 `5s`） |
| `vatica.chat.fact.enabled`（`VATICA_FACT_EXTRACTION_ENABLED`） | `true` | Agent 推断事实的异步后置抽取开关 |
| `vatica.chat.fact.max-facts-per-turn` | `3` | 每轮抽取候选上限 |
| `vatica.chat.fact.min-assistant-chars` | `120` | 回复低于该字符数跳过抽取 |
| `vatica.chat.fact.min-interval` | `30s` | 同会话两次抽取最小间隔 |

### 工具与技能

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `VATICA_AGENT_TOOL_DISCOVERY_ENABLED` | `true` | 语义发现与按需加载；关闭回退全量注册 + 词法裁剪 |
| `VATICA_AGENT_TOOL_DISCOVERY_MAX_INITIAL` | `12` | 首轮最多装载工具数 |
| `VATICA_AGENT_TOOL_DISCOVERY_MAX_RESULTS` | `8` | 单次 `search_tools` 最多再装载 |
| `VATICA_AGENT_TOOL_DISCOVERY_MAX_EXPANSIONS` | `1` | 每请求最多搜索扩展次数 |
| `VATICA_AGENT_TOOL_DISCOVERY_INITIAL_SCHEMA_TOKENS` | `6000` | 首轮 Schema 装箱预算 |
| `VATICA_AGENT_TOOL_DISCOVERY_SEARCH_SCHEMA_TOKENS` | `8000` | 搜索扩展阶段预算 |
| `VATICA_AGENT_TOOL_DISCOVERY_LEXICAL_WEIGHT` | `0.45` | 词法召回权重 |
| `VATICA_AGENT_TOOL_DISCOVERY_SEMANTIC_WEIGHT` | `0.55` | 语义（Embedding）召回权重 |
| `vatica.tool.max-calls-per-request` | `20` | 单请求工具调用上限（防死循环烧 token） |
| `vatica.tool.file.max-read-size-bytes` | `524288` | 单文件读取上限（512KB） |

### 知识库（RAG-as-a-Tool）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `VATICA_KNOWLEDGE_ENABLED` | `true` | 知识检索开关；pgvector 未就绪可设 `false` |
| `VATICA_KNOWLEDGE_EMBEDDING_PROVIDER` | `local-hash` | `local-hash` 离线学习用；生产语义召回改 `openai` |
| `VATICA_KNOWLEDGE_EMBEDDING_BASE_URL` | 空 | OpenAI 兼容 Embedding 端点 |
| `VATICA_KNOWLEDGE_EMBEDDING_API_KEY` | 空 | Embedding 密钥 |
| `VATICA_KNOWLEDGE_EMBEDDING_MODEL` | 空 | Embedding 模型名 |
| `VATICA_KNOWLEDGE_VECTOR_DIMENSIONS` | `1536` | 向量维度（需与库一致） |
| `vatica.knowledge.max-document-bytes` | `5242880` | 单文档上传上限 |
| `vatica.knowledge.chunk-size` / `chunk-overlap` | `800` / `120` | 分块参数 |

### 模型与 MCP

| 配置 | 说明 |
| --- | --- |
| `vatica.model.openai.*` | 主模型（默认 DeepSeek 端点）；`api-key` 建议留空，走前端设置页加密存储 |
| `vatica.model.qwen.*` | 备用模型（通义兼容端点）；密钥走凭据库或请求级透传 |
| `vatica.mcp.client.enabled` | MCP Client 开关（默认 `true`） |
| `vatica.mcp.client.connections.amap` | 高德 MCP；`requires-api-key: true`，key 由设置页注入，未配置时跳过连接不影响启动 |
| `vatica.mcp.client.request-timeout` | 请求超时（默认 `10s`）；初始化超时 `10s`，失败退避 `300s` |

### 任务质量与观测

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `vatica.judge.pass-threshold` | `70` | Judge 评分 ≥ 阈值判 PASS 交付 |
| `vatica.judge.max-auto-rework` | `2` | 低分自动返工上限，超限交人工 |
| `vatica.task.step-timeout` | `5m` | 单波次步骤最长等待；超时任务 FAILED，已完成步骤不回滚 |
| `vatica.evaluation.min-pass-rate` | `0.8` | 固定评测发布门禁（另含 `min-average-score: 70`、`max-failed-tool-rate: 0.1`） |
| `VATICA_OBSERVABILITY_RETENTION` | `30d` | Agent Span 保留期，启动时清理过期记录 |

### 工作区、状态与邮件

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `vatica.workspace.base-dir` | `./workspace` | 工作区根目录；实际路径固定为 `{base-dir}/{orgId}/{userId}/`，客户端只传相对路径 |
| `vatica.app.state-dir` | `./.vatica` | 内部状态（日历/待办/模型配置/master.key）目录 |
| `vatica.mail.*` | 空 | IMAP `993` / SMTP `465`；推荐由前端设置页加密配置，保存后重启生效；未配置时邮件工具返回指引，不影响其他能力 |

### 前端

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `VITE_API_BASE` | — | 构建期默认后端地址；运行时优先级为 设置页覆盖值 > `VITE_API_BASE` > `http://localhost:8080` |

## 验证与构建

后端测试使用 H2，不访问云数据库、真实模型、邮件或远程 MCP：

```powershell
cd backend
mvn test          # 523 项全绿
```

前端与打包：

```powershell
cd frontend
npm run build             # tsc + vite 生产构建
npm run tauri build       # Windows 安装包 -> src-tauri/target/release/bundle/nsis/
```

桌面壳是瘦客户端，不捆绑后端；安装后在设置中配置后端地址。

## 项目结构

```text
backend/          Spring Boot API、AgentScope 运行时、业务状态机与测试
frontend/         Tauri 2 + React 桌面客户端
docs/             按迭代归档的设计与实施报告
docker-compose.postgres.yml   本地 PostgreSQL 开发环境
任务进度.md        迭代历史、验收记录与深坑归档
任务总清单.md      任务范围与完成项
```

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [任务进度](./任务进度.md) | 当前进度、迭代记录、验收与风险 |
| [任务总清单](./任务总清单.md) | 全部工作项 |
| [项目规划](./AI办公Agent平台-项目规划.md) | 产品定位与总体规划 |
| [产品化路线](./docs/20260820_iteration23_28_productization/plan.md) | 迭代 23–28 范围与边界 |
| [迭代报告目录](./docs/) | 各迭代 plan / report 归档 |

## 许可证

[MIT](./LICENSE) © 2026 AsdsSsh

主要依赖许可证均兼容：Spring Boot / Apache POI / AgentScope（Apache-2.0），React / Ant Design / Tauri（MIT）。协议原文随各依赖包分发。
