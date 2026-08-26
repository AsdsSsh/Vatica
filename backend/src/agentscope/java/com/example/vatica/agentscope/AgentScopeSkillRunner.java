package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.runtime.AgentRegistry.AgentDefinition;
import com.example.vatica.runtime.AgentRuntime.StepRequest;
import com.example.vatica.runtime.AgentRuntime.StepResult;
import com.example.vatica.runtime.AgentRuntime.StepUsage;
import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;
import com.example.vatica.trace.TraceSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.Toolkit;

/**
 * 迭代 20B：版本化 Skill 的 AgentScope 执行器。
 * 输入工具已经过 Vatica 身份、权限、重试、Trace 和角色门禁，本类只再收窄到 manifest 声明的交集。
 */
final class AgentScopeSkillRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeSkillRunner.class);

    private final ObjectMapper mapper;
    private final Function<ModelSlot, Model> modelFactory;
    private final ContextBudget contextBudget;
    private final AgentScopeContextProperties contextProperties;

    AgentScopeSkillRunner(ObjectMapper mapper, Function<ModelSlot, Model> modelFactory) {
        this(mapper, modelFactory, new ContextBudget(0, 0, 0, 0, 0),
                new AgentScopeContextProperties(true, 0, 0, 0));
    }

    AgentScopeSkillRunner(ObjectMapper mapper, Function<ModelSlot, Model> modelFactory,
            ContextBudget contextBudget, AgentScopeContextProperties contextProperties) {
        this.mapper = mapper;
        this.modelFactory = modelFactory;
        this.contextBudget = contextBudget == null ? new ContextBudget(0, 0, 0, 0, 0) : contextBudget;
        this.contextProperties = contextProperties == null
                ? new AgentScopeContextProperties(true, 0, 0, 0) : contextProperties;
    }

    StepResult execute(StepRequest request, AgentDefinition role) {
        long start = System.nanoTime();
        ExecutionProfile skill = request.skill();
        if (skill == null) {
            throw new IllegalArgumentException("操作失败：SkillRunner 缺少执行快照。");
        }
        if (!skill.agentRole().equals(role.id())) {
            throw new IllegalStateException("操作失败：Skill " + skill.id() + "@" + skill.version()
                    + " 与 Agent 角色 " + role.id() + " 不匹配。");
        }
        List<String> traces = new ArrayList<>();
        Toolkit toolkit = new Toolkit();
        String agentName = "vatica-skill-" + skill.id() + "-" + skill.version().replace('.', '-');
        toolkit.setChunkCallback((use, result) -> {
            String output = result.getOutput().isEmpty() ? "(empty)" : result.getOutput().getFirst().toString();
            traces.add(skill.id() + "@" + skill.version() + ":" + use.getName() + " -> "
                    + TraceSanitizer.outputSummary(output, null) + " [" + result.getState() + "]");
        });
        Set<String> declaredTools = Set.copyOf(skill.tools());
        AgentScopeToolGroupAdapter.Registration registration = AgentScopeToolGroupAdapter.register(
                toolkit, request.tools(), declaredTools, true);
        if (!registration.missingAllowedToolNames().isEmpty()) {
            throw new IllegalStateException("操作失败：Skill " + skill.id() + "@" + skill.version()
                    + " 的授权工具不可用（" + String.join(", ", registration.missingAllowedToolNames()) + "）。");
        }
        Model model = modelFactory.apply(request.modelSlot());
        String prompt = systemPrompt(role, skill);
        AgentScopeContextBudgetMiddleware budgetMiddleware = new AgentScopeContextBudgetMiddleware(model,
                prompt, ContextBudget.CallSite.EXECUTOR,
                contextBudget.tokensFor(ContextBudget.CallSite.EXECUTOR), contextProperties);
        ReActAgent agent = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(prompt)
                .model(model)
                .toolkit(toolkit)
                // 迭代 30B/30D：Skill 的每次模型调用都经过预算和执行门禁 middleware。
                .middleware(budgetMiddleware)
                .maxIters(skill.limits().maxIterations())
                .defaultSessionId(request.sessionId())
                .generateOptions(GenerateOptions.builder().reasoningEffort("none")
                        .toolChoice(new ToolChoice.Auto()).build())
                .build();
        try {
            RuntimeContext context = RuntimeContext.builder()
                    .userId(String.valueOf(request.identity().userId()))
                    .sessionId(request.sessionId())
                    .build();
            var reply = agent.call(List.of(new UserMessage(AgentScopeRuntime.stepPrompt(request))), context).block();
            String answer = reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
            ChatUsage usage = reply == null ? null : reply.getChatUsage();
            StepUsage stepUsage = usage == null ? null : new StepUsage(
                    usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(), usage.getCachedTokens());
            log.info("AgentScope SkillRunner skill={}@{} role={} toolkit={}",
                    skill.id(), skill.version(), role.id(), skill.tools());
            return new StepResult(answer, traces, (System.nanoTime() - start) / 1_000_000, stepUsage);
        } finally {
            agent.close();
        }
    }

    private static String systemPrompt(AgentDefinition role, ExecutionProfile skill) {
        return """
                你是 Vatica Skill 执行 Agent。只执行当前步骤，只使用工具返回的数据，工具未返回的数据不得编造。
                工具失败时如实说明原因，不得假装成功。身份、权限、审批与任务状态由 Vatica 管理。
                当前 Skill：%s@%s（%s），角色：%s。
                Skill 入口约束：%s
                声明权限标签：%s。权限标签只用于治理和审计，不代表授予；实际授权以 Vatica 工具回调为准。
                资源上限：推理轮次 %d，工具调用 %d 次，单次工具输出 %d 字符。
                完成后优先输出 JSON：{"result":"结果","notes":[],"needHelp":null,"discoveries":[]}。
                needHelp 只用于确实无法继续的求助，discoveries 最多提出 2 个必要补充步骤。
                """.formatted(skill.id(), skill.version(), skill.displayName(), role.displayName(),
                        skill.entryPrompt(), String.join(", ", skill.permissions()),
                        skill.limits().maxIterations(), skill.limits().maxToolCalls(),
                        skill.limits().maxOutputChars()) + role.systemPrompt();
    }
}
