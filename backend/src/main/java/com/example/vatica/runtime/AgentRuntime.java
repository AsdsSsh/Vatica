package com.example.vatica.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import io.agentscope.core.tool.AgentTool;
import com.example.vatica.agentscope.AgentToolAdapters;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.runtime.AgentRegistry.AgentDefinition;
import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;
import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 迭代 15 I15-17：双运行时边界——LegacyRuntime（Spring AI 现状实现）与
 * AgentScopeRuntime（-Pagentscope profile 加载的对照实现）实现同一 POC 契约。
 * 领域状态机 / JPA / HITL / 多租户与权限规则仍是唯一事实源。
 */
public interface AgentRuntime {

    String name();

    /** 迭代 20C：AgentScope 只生成建议，调用方继续负责结构校验与业务决策。 */
    enum AdvisoryKind {
        PLAN,
        PLAN_REVISION,
        COLLABORATION,
        JUDGE
    }

    /** 无工具建议请求；modelSlot 为空时由运行时按 Planner/Judge 能力选择平台槽位。 */
    record AdvisoryRequest(AdvisoryKind kind, String systemPrompt, String userPrompt,
            RequestIdentity identity, ModelSlot modelSlot, String sessionId) {
        public AdvisoryRequest {
            kind = Objects.requireNonNull(kind, "kind");
            systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
            userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
            identity = Objects.requireNonNull(identity, "identity");
            sessionId = sessionId == null || sessionId.isBlank()
                    ? "advisory-" + java.util.UUID.randomUUID() : sessionId;
        }
    }

    /** 原始建议必须回到 Planner/Judge 解析，运行时不得直接产出业务状态。 */
    record AdvisoryResult(String content, long durationMs, StepUsage usage) {
        public AdvisoryResult {
            content = content == null ? "" : content;
        }
    }

    /** LegacyRuntime 返回 empty，由既有 Spring AI 结构化输出链路接管。 */
    default Optional<AdvisoryResult> advise(AdvisoryRequest request) {
        return Optional.empty();
    }

    record PovResult(String answer, List<String> toolTraces, long durationMs) {
    }

    /** 迭代 17A：Vatica 编排层交给运行时的单步骤快照。 */
    record StepRequest(String goal, TaskStep step, List<String> context, String reflectionFeedback,
            RequestIdentity identity, AgentTool[] tools, ChatClient legacyClient,
            ModelSlot modelSlot, AgentDefinition agent, String sessionId, ExecutionProfile skill) {
        public StepRequest {
            context = context == null ? List.of() : List.copyOf(context);
            tools = tools == null ? new AgentTool[0] : tools.clone();
        }

        @Override
        public AgentTool[] tools() {
            return tools.clone();
        }

        /** 兼容 17A 的运行时单测与 POC；未绑定 Skill 时保留角色级执行。 */
        public StepRequest(String goal, TaskStep step, List<String> context, String reflectionFeedback,
                RequestIdentity identity, ToolCallback[] toolCallbacks, ChatClient legacyClient,
                ModelSlot modelSlot, AgentDefinition agent, String sessionId) {
            this(goal, step, context, reflectionFeedback, identity,
                    AgentToolAdapters.fromCallbacks(toolCallbacks, new com.fasterxml.jackson.databind.ObjectMapper()), legacyClient,
                    modelSlot, agent, sessionId, null);
        }

        /** 迁移期兼容：带 Skill 的旧 ToolCallback 构造器。 */
        public StepRequest(String goal, TaskStep step, List<String> context, String reflectionFeedback,
                RequestIdentity identity, ToolCallback[] toolCallbacks, ChatClient legacyClient,
                ModelSlot modelSlot, AgentDefinition agent, String sessionId, ExecutionProfile skill) {
            this(goal, step, context, reflectionFeedback, identity,
                    AgentToolAdapters.fromCallbacks(toolCallbacks, new com.fasterxml.jackson.databind.ObjectMapper()),
                    legacyClient, modelSlot, agent, sessionId, skill);
        }
    }

    /** 直连模型运行时返回的最小用量快照；null 表示 provider 未返回 usage。 */
    record StepUsage(int inputTokens, int outputTokens, int totalTokens, long cacheReadTokens) {
    }

    record StepResult(String answer, List<String> toolTraces, long durationMs, StepUsage usage) {
        public StepResult {
            answer = answer == null ? "" : answer;
            toolTraces = toolTraces == null ? List.of() : List.copyOf(toolTraces);
        }

        /** LegacyRuntime 的用量由 Spring AI UsageAdvisor 负责，保持旧构造兼容。 */
        public StepResult(String answer, List<String> toolTraces, long durationMs) {
            this(answer, toolTraces, durationMs, null);
        }
    }

    /** 生产任务主链的唯一运行时入口；业务状态不进入运行时。 */
    StepResult executeStep(StepRequest request);

    /** 单 Agent 工具链 POC：目标 + 身份 + 权限快照 → 最终回答与工具轨迹。 */
    PovResult runSingleAgent(String goal, RequestIdentity identity, FilePermissionPolicy permission);

    /** 迭代 15 I15-20：双 Agent 黑板 POC——document/workspace 两个角色经黑板 note 协作。 */
    PovResult runDualAgentBlackboard(String goal, RequestIdentity identity, FilePermissionPolicy permission);
}
