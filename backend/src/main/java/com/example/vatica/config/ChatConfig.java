package com.example.vatica.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.agent.JudgeAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.controller.ChatMessageRecordRepository;
import com.example.vatica.controller.ChatSessionRecordRepository;
import com.example.vatica.controller.InMemorySessionMemory;
import com.example.vatica.controller.JpaSessionMemory;
import com.example.vatica.controller.SessionMemory;
import com.example.vatica.controller.SessionSummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 对话层装配（迭代 2.5 会话记忆；迭代 4 MCP 工具合并；迭代 5 ChatClient Bean 化 + 会话持久化；
 * 迭代 5.5 Judge Agent；迭代 8.5 三个客户端改为动态委托——默认模型改配置即时生效）。
 *
 * <p>ChatClient 分工（面试可讲"关注点分离"）：
 * <ul>
 *   <li><b>vaticaChatClient</b>：聊天 + 任务执行——动态委托 {@link ModelRegistry} 的默认模型（带本地工具 + MCP 韧性包装）</li>
 *   <li><b>plannerChatClient</b>：规划专用，无工具——规划阶段只做分解不执行，防副作用</li>
 *   <li><b>judgeChatClient</b>：评测专用，无工具——评测只读执行材料，不执行任何操作</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({
        ChatProperties.class, JudgeProperties.class, ModelProperties.class, OpenAiDefaultsProperties.class,
        TaskReliabilityProperties.class,
        com.example.vatica.evaluation.EvaluationProperties.class,
        com.example.vatica.usage.UsageProperties.class,
        com.example.vatica.runtime.AgentRuntimeProperties.class })
public class ChatConfig {

    /** 会话短期记忆：内存滑窗热缓存 + MySQL 落库（迭代 5 I5-4；迭代 15 增加中期摘要）。 */
    @Bean
    SessionMemory sessionMemory(ChatMessageRecordRepository repository, ChatSessionRecordRepository sessions,
            SessionSummaryService summaryService, ChatProperties props) {
        InMemorySessionMemory cache = new InMemorySessionMemory(
                props.memory().maxMessages(), props.memory().maxSessions(), props.memory().maxChars());
        return new JpaSessionMemory(cache, repository, props.memory().maxMessages(), sessions, summaryService);
    }

    /** 迭代 15 I15-8：各调用点 token 预算（先使用已定稿默认值，后续可迁配置中心）。 */
    @Bean
    com.example.vatica.context.ContextBudget contextBudget() {
        return new com.example.vatica.context.ContextBudget(0, 0, 0, 0, 0);
    }

    /** 迭代 15 I15-13：用量观测 advisor（ModelRegistry 所有动态客户端统一挂载）。 */
    @Bean
    com.example.vatica.usage.UsageAdvisor usageAdvisor(com.example.vatica.usage.UsageRecorder recorder,
            com.example.vatica.usage.UsageQuotaService quotaService) {
        return new com.example.vatica.usage.UsageAdvisor(recorder, quotaService);
    }

    /**
     * JSON 序列化（迭代 5）：Boot 4.1 未自动装配 ObjectMapper Bean，这里显式声明供
     * Planner/任务层/权限事件复用。迭代 12 热修：注册 JavaTimeModule——
     * PermissionEventPublisher 序列化 FilePermissionRequest.createdAt（Instant）时
     * 若缺失会抛 InvalidDefinitionException，导致"权限弹窗无订阅者"假象。
     */
    @Bean
    ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper;
    }

    /**
     * 主对话/执行客户端（迭代 8.5）：动态委托默认模型——界面改配置（含换模型/换 Key）
     * 即时生效，ExecutorAgent 等既有注入方零改动。
     */
    @Bean
    ChatClient vaticaChatClient(ModelRegistry registry) {
        return new DelegatingChatClient(registry::defaultClient);
    }

    /** 规划专用客户端：无工具（规划不执行），随默认模型动态切换。 */
    @Bean
    ChatClient plannerChatClient(ModelRegistry registry) {
        return new DelegatingChatClient(registry::plannerClient);
    }

    /** 评测专用客户端：无工具（评测只读材料、不执行任何操作），随默认模型动态切换。 */
    @Bean
    ChatClient judgeChatClient(ModelRegistry registry) {
        return new DelegatingChatClient(registry::judgeClient);
    }

    /** 迭代 5 I5-1：Planner Agent（迭代 15 起工具清单从 ToolCallbackProvider 动态生成）。 */
    @Bean
    PlannerAgent plannerAgent(ChatClient plannerChatClient, ObjectMapper objectMapper,
            com.example.vatica.runtime.AgentToolCatalog agentTools,
            com.example.vatica.runtime.AgentRegistry agentRegistry) {
        return new PlannerAgent(plannerChatClient, objectMapper, agentTools::callbacks, agentRegistry);
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
