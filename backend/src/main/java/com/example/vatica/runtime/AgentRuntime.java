package com.example.vatica.runtime;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

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

    record PovResult(String answer, List<String> toolTraces, long durationMs) {
    }

    /** 迭代 17A：Vatica 编排层交给运行时的单步骤快照。 */
    record StepRequest(String goal, TaskStep step, List<String> context, String reflectionFeedback,
            RequestIdentity identity, ToolCallback[] toolCallbacks, ChatClient legacyClient,
            ModelSlot modelSlot, AgentDefinition agent, String sessionId, ExecutionProfile skill) {
        public StepRequest {
            context = context == null ? List.of() : List.copyOf(context);
            toolCallbacks = toolCallbacks == null ? new ToolCallback[0] : toolCallbacks.clone();
        }

        @Override
        public ToolCallback[] toolCallbacks() {
            return toolCallbacks.clone();
        }

        /** 兼容 17A 的运行时单测与 POC；未绑定 Skill 时保留角色级执行。 */
        public StepRequest(String goal, TaskStep step, List<String> context, String reflectionFeedback,
                RequestIdentity identity, ToolCallback[] toolCallbacks, ChatClient legacyClient,
                ModelSlot modelSlot, AgentDefinition agent, String sessionId) {
            this(goal, step, context, reflectionFeedback, identity, toolCallbacks, legacyClient,
                    modelSlot, agent, sessionId, null);
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
