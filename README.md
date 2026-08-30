# Vatica

Vatica 是一个把个人办公任务从自然语言指令推进到可审计结果的桌面 Agent。

它不是通用聊天壳，也不试图复刻 Codex 一类编程 Agent。Vatica 聚焦个人办公中的高频闭环：读取日程和已授权资料、形成可核对的草案、展示即将发生的副作用，并在用户批准后交付文档、待办或邮件草稿。

当前已完成的代表性流程是会议准备：

```text
选择日历会议
  -> 读取日历事实与可选授权资料
  -> 展示来源、建议议程、待确认项、文档和待办差异
  -> 用户批准或拒绝
  -> 写入 Markdown 准备文档和待办，并可回看产物
```

## 项目定位

Vatica 的产品重点是三件事：

| 重点 | 当前实现 |
| --- | --- |
| 高频办公闭环 | 日历、待办、邮件、工作区文件、Word/Excel 交付与会议准备 |
| 可靠执行 | Skill 能力匹配、权限门禁、HITL 审批、幂等写入、失败与降级状态 |
| 用户可控 | 输入事实来源、待办/文档差异预览、批准记录、任务产物与运行观测 |

模型负责理解、规划和生成建议；Vatica 负责业务事实、身份与租户隔离、权限、审批、状态转换、持久化和审计。这避免把模型输出直接当作可以执行的命令。

## 当前进度

| 版本 | 状态 | 结果 |
| --- | --- | --- |
| 迭代 22 | 已完成 | AgentScope 成为唯一 Agent 运行时，移除 Spring AI 任务运行时 |
| 迭代 23 | 已完成 | Skill 能力匹配、依赖就绪度、认证/CORS 体验和 H2 端到端回归 |
| 迭代 24 | 已完成 | 会议准备闭环：候选确认、证据草案、批准写入、待办与 Markdown 产物回看 |
| 迭代 25～30 | 已完成 | 可控执行、周报、知识库、可观测运营、上下文韧性与 AgentScope 模型感知运行时 |
| 迭代 31A～31C | 已完成 | 分层上下文预算、长历史按需回源、可追溯摘要段与历史证据检索 |
| 迭代 31D | 已完成 | 将预算计划和历史证据接入 AgentScope 聊天/每轮中间件，补齐观测与前端模式选择 |
| 迭代 32 | 已完成 | 工具语义发现与按需加载：动态 ToolGroup、能力匹配进入聊天上下文 |
| 迭代 33 | 已完成 | 摘要失败降级五类材料：工具、审批、任务状态与交付物记录统一注入，缺失不等于未发生 |

最新已记录回归：迭代 33 全量 `mvn test` 510 项全绿；前端 `npm run build` 通过。

详细进度见 [任务进度](./任务进度.md)、[任务总清单](./任务总清单.md) 和 [产品化路线](./docs/20260820_iteration23_28_productization/plan.md)。

## 已交付能力

### 办公闭环

- **会议准备**：用户显式选择会议，不按标题相似度自动合并；会议标题和时间在创建草案时快照保存。
- **证据化草案**：日历事实、用户目标、建议、待确认事项、授权资料引用和待办差异分开呈现；知识检索不可用时显式降级，不编造结论。
- **受控写入**：文档和待办在批准前只显示预览；批准后创建 Markdown 文档和待办，重复批准不会重复写入；拒绝不产生副作用。
- **个人办公工具**：受权限与租户边界约束的工作区文件、日历、待办、邮件、Word 和 Excel 能力。
- **任务工作台**：自然语言任务、计划审批、步骤进度、取消、返工、Judge 评分和交付物入口。

### Agent 运行与治理

- **AgentScope 唯一运行时**：负责模型、消息、原生工具循环、Planner/Judge 建议与 MCP Client；不再保留 Spring AI 或 Legacy Agent 运行时。
- **Skill 受控执行**：Planner 先匹配任务所需能力，运行时只注册“用户授权 ∩ Agent 白名单 ∩ Skill 声明”的工具集合。
- **人工介入**：计划和高风险写入操作经过 HITL；重规划改变已批准的副作用范围时必须重新确认。
- **质量门禁**：Judge 按评分阈值评估任务结果；低分任务在预算内返工，超限交由用户处理。
- **可观测性**：以 `agent_span` 记录任务、规划、Wave、Agent、模型、工具、HITL 和 Judge 的脱敏摘要，可在工作台查看链路和失败点。

### 身份、数据与安全边界

- 支持本地学习模式与 JWT 多用户模式；云端部署应设置 `VATICA_AUTH_ENABLED=true`。
- 任务、会话、日历、待办、工作区、知识库和观测数据按用户/组织隔离。
- 工作区仅接受相对路径，拒绝目录穿越和符号链接逃逸；权限策略由服务端持久化。
- 模型、邮件和集成凭据不写入 Git；可使用临时凭据或加密持久化配置。
- 不保存原始思维链、完整 Prompt 或密钥，只保留可用于诊断的脱敏运行摘要。

## 架构

```text
Tauri 2 Desktop Shell
  React 19 + Ant Design 6
  - 对话、个人工作台、会议准备、Agent 可观测性
  - REST / SSE；服务地址可在设置页配置
                 |
                 v
Spring Boot 4.1 API (Java 21)
  - 身份、租户、权限、状态机、审计、OpenAPI 契约
  - 任务：能力匹配 -> Planner -> 并行 Wave -> HITL -> Judge
  - 会议准备：候选确认 -> 草案 -> 批准 -> 文档/待办产物
  - AgentScope 2.0.2：模型、工具循环、Skill Runner、MCP Client
                 |
        +--------+---------+----------------+
        |                  |                |
        v                  v                v
  PostgreSQL          本地办公工具       MCP 服务
  业务事实源          文件/日历/待办      高德等远程能力
  H2 用于测试         邮件/Word/Excel     失败时受控降级
```

后端是纯 API 服务，`GET /v3/api-docs` 是前后端 API 契约入口。桌面前端只通过 HTTP API 与后端通信，API 基址优先级为：设置页本地存储 > `VITE_API_BASE` > `http://localhost:8080`。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Spring Boot 4.1、Java 21、虚拟线程、Spring Data JPA、springdoc |
| Agent | AgentScope Java 2.0.2、官方 MCP Java SDK 0.17 |
| 数据 | PostgreSQL 为开发/云端主库；H2 用于单测与打包零依赖模式 |
| 前端 | Tauri 2、React 19、TypeScript、Vite、Ant Design 6 |
| 办公能力 | Apache POI、Jakarta Mail、ICS 日历、工作区文件服务 |
| 验证 | JUnit 5、AssertJ、GreenMail、前端 TypeScript/Vite 构建 |

`pgvector` 是后续可信知识库能力的前置条件，不是当前启动或会议准备的必需条件。它不可用时，知识检索会显示为未配置或降级，其他办公流程保持可用。

## 快速开始

### 前置条件

- Java 21
- Node.js 当前 LTS 和 npm
- PostgreSQL 16+（本地或云端）；测试不需要外部数据库
- Windows 打包时还需要 Rust stable、Visual Studio C++ Build Tools 和 WebView2 Runtime

### 1. 配置数据库并启动后端

开发或云端环境使用 PostgreSQL。以下变量只对当前 PowerShell 会话生效，避免把密码写入仓库：

```powershell
$env:POSTGRES_HOST = "127.0.0.1"
$env:POSTGRES_PORT = "5432"
$env:POSTGRES_DATABASE = "vatica"
$env:POSTGRES_USERNAME = "vatica"
$env:POSTGRES_PASSWORD = "<数据库密码>"

# 本地学习模式可保持 false；云端部署必须改为 true。
$env:VATICA_AUTH_ENABLED = "false"

# 尚未安装 pgvector 时，明确关闭知识检索即可运行其余流程。
$env:VATICA_KNOWLEDGE_ENABLED = "false"

cd backend
mvn spring-boot:run
```

如需启动本地 PostgreSQL 容器：

```powershell
$env:POSTGRES_PASSWORD = "vatica-local"
docker compose -f docker-compose.postgres.yml up -d
```

后端启动后可访问：

```text
http://localhost:8080/                 API 索引
http://localhost:8080/v3/api-docs      OpenAPI JSON 契约
http://localhost:8080/swagger-ui.html  Swagger UI
```

### 2. 启动桌面前端

```powershell
cd frontend
npm ci
npm run tauri dev
```

仅调试 Web UI：

```powershell
cd frontend
npm run dev
# http://localhost:1420
```

在桌面端的“设置”中配置后端服务地址和模型。模型可使用 OpenAI 兼容端点（如 DeepSeek、通义兼容接口）或已支持的 Anthropic 协议；密钥不应提交到 Git。

Windows 上 `npm ci` 若报 `EPERM` 且指向 `esbuild.exe`，先关闭正在运行的 Vite/Tauri 进程和占用该文件的编辑器，再重新执行安装命令。

### 3. 体验会议准备

1. 在个人工作台创建或导入日程。
2. 打开右侧任务面板的“会议准备”。
3. 选择日期范围和一场明确的会议，可补充准备目标和授权资料检索选项。
4. 检查来源、建议议程、待确认事项、待办差异和 Markdown 文档预览。
5. 批准后查看创建的待办和下载入口；拒绝则不创建文档或待办。

## 验证与构建

后端测试使用 H2，不访问云数据库、真实模型、邮件或远程 MCP：

```powershell
cd backend
mvn test
```

前端生产构建：

```powershell
cd frontend
npm run build
```

构建 Windows 安装包：

```powershell
cd frontend
npm run tauri build
```

安装包输出在 `frontend/src-tauri/target/release/bundle/nsis/`。当前桌面壳是瘦客户端，不捆绑后端服务；安装后需要在设置中配置可访问的 Vatica 后端地址。

## 关键配置

| 配置 | 用途 |
| --- | --- |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DATABASE` | PostgreSQL 连接地址与数据库名 |
| `POSTGRES_USERNAME` / `POSTGRES_PASSWORD` | PostgreSQL 凭据 |
| `VATICA_AUTH_ENABLED` | `false` 为本地学习模式；生产多用户环境设为 `true` |
| `VATICA_KNOWLEDGE_ENABLED` | 是否启用知识检索；pgvector 未就绪时可设为 `false` |
| `VATICA_WORKSPACE_BASE_DIR` | 用户工作区根目录，实际路径按组织和用户隔离 |
| `VATICA_CORS_ALLOWED_ORIGINS` | 额外允许的开发来源，逗号分隔 |
| `VATICA_OBSERVABILITY_RETENTION` | Agent Span 保留期，默认 `30d` |
| `VITE_API_BASE` | 前端构建期默认后端地址，可被设置页覆盖 |

完整默认配置见 [backend/src/main/resources/application.yml](./backend/src/main/resources/application.yml)。

## 项目结构

```text
backend/                         Spring Boot API、AgentScope、业务状态机与测试
frontend/                        Tauri + React 桌面客户端
docs/20260820_iteration24_.../  会议准备闭环报告
docs/20260820_iteration23_28.../ 产品化路线与后续迭代设计
docker-compose.postgres.yml      本地 PostgreSQL 开发环境
任务进度.md                      迭代历史、当前状态与验收记录
任务总清单.md                    任务范围和完成项
```

## 后续路线

1. **迭代 34：摘要失败降级材料再加固**。补齐任务关联聊天注入、材料预算归一和异常注入演练；迭代 33 已交付五类材料统一注入。
2. **迭代 27 云端依赖验收**。云端 PostgreSQL 安装并启用 pgvector 后，再执行 readiness 和云端知识库评测。
3. **后续专项**。按实际需求安排 tokenizer 适配、工具选择评测、Docker、多实例和云端部署；P1 项保持待排期。

不在路线内的内容包括：保存原始思维链、无边界的多 Agent 自主协作、未经批准的邮件发送，以及为方便开发而放宽租户或文件权限边界。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [任务进度](./任务进度.md) | 当前进度、迭代记录、验收和风险 |
| [任务总清单](./任务总清单.md) | 已完成与规划中的全部工作项 |
| [产品化路线](./docs/20260820_iteration23_28_productization/plan.md) | 迭代 23 至 28 的范围、边界与完成定义 |
| [迭代 24 报告](./docs/20260820_iteration24_meeting_preparation/report.md) | 会议准备的设计、接口和验证记录 |
