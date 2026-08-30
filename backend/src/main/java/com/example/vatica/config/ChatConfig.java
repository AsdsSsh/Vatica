package com.example.vatica.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * 对话层装配。迭代 22D 起聊天、规划、执行和评测统一由 AgentScope 运行时承载，
 * Vatica 仅装配会话、预算、领域 Agent 与状态机依赖。
 */
@Configuration
@EnableConfigurationProperties({
        ChatProperties.class, JudgeProperties.class, ModelProperties.class, OpenAiDefaultsProperties.class,
        TaskReliabilityProperties.class,
        AgentScopeContextProperties.class,
        ToolDiscoveryProperties.class,
        ContextAllocationProperties.class,
        ConversationEvidenceProperties.class,
        com.example.vatica.evaluation.EvaluationProperties.class,
        com.example.vatica.usage.UsageProperties.class })
public class ChatConfig {

    /** 会话短期记忆：内存滑窗热缓存 + PostgreSQL 落库（迭代 5 I5-4；迭代 15 增加中期摘要；34 增加推断事实抽取触发）。 */
    @Bean
    SessionMemory sessionMemory(ChatMessageRecordRepository repository, ChatSessionRecordRepository sessions,
            SessionSummaryService summaryService,
            com.example.vatica.context.ContextFactExtractionService factExtraction, ChatProperties props) {
        InMemorySessionMemory cache = new InMemorySessionMemory(
                props.memory().maxMessages(), props.memory().maxSessions(), props.memory().maxChars());
        return new JpaSessionMemory(cache, repository, props.memory().maxMessages(), sessions, summaryService,
                factExtraction, props.memory().longContextMaxMessages());
    }

    /** 迭代 15 I15-8：各调用点 token 预算（先使用已定稿默认值，后续可迁配置中心）。 */
    @Bean
    com.example.vatica.context.ContextBudget contextBudget() {
        return new com.example.vatica.context.ContextBudget(0, 0, 0, 0, 0);
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

    /** Planner 保留计划结构、角色与工具门禁，模型建议经 AgentScopeRuntime 获取。 */
    @Bean
    PlannerAgent plannerAgent(ObjectMapper objectMapper,
            com.example.vatica.runtime.AgentToolCatalog agentTools,
            com.example.vatica.runtime.AgentRegistry agentRegistry,
            com.example.vatica.runtime.AgentRuntimeFactory runtimeFactory) {
        return new PlannerAgent(objectMapper, agentTools, agentRegistry, runtimeFactory);
    }

    /** 迭代 5.5 I5.5-1：Judge Agent（评分卡 + 规则校验先行 + 解析降级）。 */
    @Bean
    JudgeAgent judgeAgent(ObjectMapper objectMapper, JudgeProperties judgeProperties,
            com.example.vatica.runtime.AgentRuntimeFactory runtimeFactory) {
        return new JudgeAgent(objectMapper, judgeProperties.passThreshold(), runtimeFactory);
    }
}
