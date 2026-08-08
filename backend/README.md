# backend — Vatica 后端

**技术栈**：Spring Boot 4.1 + Spring AI 2.0 + MCP Java SDK + Java 21

## 启动

1. 设置环境变量 `DEEPSEEK_API_KEY`（DeepSeek 开放平台申请，充值 10 元够开发用）
2. `mvn spring-boot:run`
3. 验证：
   - 非流式：`curl -X POST localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"message\":\"你好\"}"`
   - 流式：`curl -N -X POST localhost:8080/api/chat/stream -H "Content-Type: application/json" -d "{\"message\":\"你好\"}"`

## 包结构（按最终架构分层）

```
com.example.vatica
├── controller/   REST/SSE 接口（迭代 1：ChatController）
├── service/      业务逻辑（后续填充）
├── config/       配置（后续填充）
├── tool/         工具层（迭代 2：文件/文档工具）
├── agent/        Agent 编排（迭代 5：Planner/多 Agent）
└── task/         任务状态机（迭代 5：HITL 审批）
```

## 里程碑

- 迭代 1：能聊天的后端（SSE 流式）— 已完成
- 迭代 2：工具调用（ToolCallingAdvisor）
- 迭代 3：POI 文档生成（Word/Excel）
- 迭代 4：MCP Server + Client
- 迭代 5：Planner 拆解 + HITL 审批 + 持久化
