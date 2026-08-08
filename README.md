# AI 办公 Agent 平台

对标腾讯 WorkBuddy / 字节 TRAE Work / 阿里千问办公的 AI 办公执行桌面应用（简历项目）。

**核心闭环**：一句话 → 任务拆解 → 多 Agent 调用工具 → 人工审批 → 交付 Word/Excel 成品

## 技术栈

| 端 | 技术 |
|---|---|
| 后端 | Spring Boot 4.1 + Spring AI 2.0 + MCP Java SDK + Apache POI（Java 21） |
| 前端 | Tauri 2 + React 19（桌面应用） |
| 模型 | DeepSeek（OpenAI 兼容 API，可换通义千问） |

## 目录结构

| 目录 | 说明 |
|---|---|
| `backend/` | Spring Boot 后端（Agent 编排 + 工具层 + MCP） |
| `frontend/` | Tauri + React 桌面壳 |

## 项目文档

- `AI办公Agent平台-项目规划.md` — 规划与参考资料
- `任务总清单.md` — 全部任务一览（规划用）
- `任务进度.md` — 每周进度、验收标准、风险记录

## 快速开始（后端）

1. 设置环境变量 `DEEPSEEK_API_KEY`（DeepSeek 开放平台申请）
2. `cd backend && mvn spring-boot:run`
3. 验证流式对话：
   `curl -N -X POST localhost:8080/api/chat/stream -H "Content-Type: application/json" -d "{\"message\":\"你好\"}"`
