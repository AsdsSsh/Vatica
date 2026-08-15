package com.example.vatica.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.agent.JudgeAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.controller.ChatMessageRecordRepository;
import com.example.vatica.controller.InMemorySessionMemory;
import com.example.vatica.controller.JpaSessionMemory;
import com.example.vatica.controller.SessionMemory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 对话层装配（迭代 2.5 会话记忆；迭代 4 MCP 工具合并；迭代 5 ChatClient Bean 化 + 会话持久化；
 * 迭代 5.5 Judge Agent）。
 *
 * <p>ChatClient 分工（面试可讲"关注点分离"）：
 * <ul>
 *   <li><b>vaticaChatClient</b>：聊天 + 任务执行——本地工具与 MCP 远程工具合并进 defaultTools</li>
 *   <li><b>plannerChatClient</b>：规划专用，无工具——规划阶段只做分解不执行，防副作用</li>
 *   <li><b>judgeChatClient</b>：评测专用，无工具——评测只读执行材料，不执行任何操作</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({ ChatProperties.class, JudgeProperties.class })
public class ChatConfig {

    /** 会话短期记忆：内存滑窗热缓存 + MySQL 落库（迭代 5 I5-4）。 */
    @Bean
    SessionMemory sessionMemory(ChatMessageRecordRepository repository, ChatProperties props) {
        InMemorySessionMemory cache = new InMemorySessionMemory(
                props.memory().maxMessages(), props.memory().maxSessions(), props.memory().maxChars());
        return new JpaSessionMemory(cache, repository, props.memory().maxMessages());
    }

    /** JSON 序列化（迭代 5）：Boot 4.1 未自动装配 ObjectMapper Bean，这里显式声明供 Planner/任务层复用。 */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /** 主对话/执行客户端：本地工具 + MCP 远程工具（迭代 4 合并进 defaultTools；迭代 8 韧性包装）。 */
    @Bean
    ChatClient vaticaChatClient(ChatClient.Builder builder, ToolCallbackProvider vaticaTools,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpToolProvider) {
        SyncMcpToolCallbackProvider mcpTools = mcpToolProvider.getIfAvailable();
        return mcpTools == null
                ? builder.defaultTools(vaticaTools).build()
                : builder.defaultTools(vaticaTools, new McpToolProviderGuard(mcpTools)).build();
    }

    /** 规划专用客户端：无工具（规划不执行）。 */
    @Bean
    ChatClient plannerChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** 评测专用客户端：无工具（评测只读材料、不执行任何操作）。 */
    @Bean
    ChatClient judgeChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** 迭代 5 I5-1：Planner Agent。 */
    @Bean
    PlannerAgent plannerAgent(ChatClient plannerChatClient, ObjectMapper objectMapper) {
        return new PlannerAgent(plannerChatClient, objectMapper);
    }

    /** 迭代 5：Executor Agent（复用主客户端全部工具）。 */
    @Bean
    ExecutorAgent executorAgent(ChatClient vaticaChatClient) {
        return new ExecutorAgent(vaticaChatClient);
    }

    /** 迭代 5.5 I5.5-1：Judge Agent（评分卡 + 规则校验先行 + 解析降级）。 */
    @Bean
    JudgeAgent judgeAgent(ChatClient judgeChatClient, ObjectMapper objectMapper, JudgeProperties judgeProperties) {
        return new JudgeAgent(judgeChatClient, objectMapper, judgeProperties.passThreshold());
    }
}
