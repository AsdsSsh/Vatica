package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionBoundToolCallbacks;
import com.example.vatica.tool.AgentToolProvider;
import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.runtime.AgentRuntime;
import com.example.vatica.runtime.AgentRuntime.AdvisoryRequest;
import com.example.vatica.runtime.AgentRuntime.AdvisoryResult;
import com.example.vatica.trace.TraceSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 迭代 22D：AgentScope 单/双 Agent 生产运行时。
 * 使用 Vatica 的真实模型槽位/API Key、工具回调、身份快照与权限快照；
 * AgentScope 只负责 ReAct 循环，不建立第二套业务状态机。
 */
public class AgentScopeRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeRuntime.class);

    private final ModelRegistry registry;
    private final AgentToolProvider vaticaTools;
    private final ObjectMapper mapper;
    private final AgentRegistry agentRegistry;
    private final Function<ModelSlot, Model> modelFactory;
    private final ContextBudget contextBudget;
    private final AgentScopeContextProperties contextProperties;
    private final AgentScopeSkillRunner skillRunner;

    public AgentScopeRuntime(ModelRegistry registry, AgentToolProvider vaticaTools, ObjectMapper mapper) {
        this(registry, vaticaTools, mapper, new AgentRegistry(),
                new ContextBudget(0, 0, 0, 0, 0), new AgentScopeContextProperties(true, 0, 0, 0));
    }

    public AgentScopeRuntime(ModelRegistry registry, AgentToolProvider vaticaTools, ObjectMapper mapper,
            AgentRegistry agentRegistry) {
        this(registry, vaticaTools, mapper, agentRegistry, registry::agentScopeModel,
                new ContextBudget(0, 0, 0, 0, 0), new AgentScopeContextProperties(true, 0, 0, 0));
    }

    AgentScopeRuntime(ModelRegistry registry, AgentToolProvider vaticaTools, ObjectMapper mapper,
            AgentRegistry agentRegistry, Function<ModelSlot, Model> modelFactory) {
        this(registry, vaticaTools, mapper, agentRegistry, modelFactory,
                new ContextBudget(0, 0, 0, 0, 0), new AgentScopeContextProperties(true, 0, 0, 0));
    }

    /**
     * 迭代 30B：生产运行时接收统一上下文预算配置；保留旧构造器供历史构建产物和单测使用。
     */
    public AgentScopeRuntime(ModelRegistry registry, AgentToolProvider vaticaTools, ObjectMapper mapper,
            AgentRegistry agentRegistry, ContextBudget contextBudget,
            AgentScopeContextProperties contextProperties) {
        this(registry, vaticaTools, mapper, agentRegistry, registry::agentScopeModel,
                contextBudget, contextProperties);
    }

    AgentScopeRuntime(ModelRegistry registry, AgentToolProvider vaticaTools, ObjectMapper mapper,
            AgentRegistry agentRegistry, Function<ModelSlot, Model> modelFactory,
            ContextBudget contextBudget, AgentScopeContextProperties contextProperties) {
        this.registry = registry;
        this.vaticaTools = vaticaTools;
        this.mapper = mapper;
        this.agentRegistry = agentRegistry;
        this.modelFactory = modelFactory;
        this.contextBudget = contextBudget == null ? new ContextBudget(0, 0, 0, 0, 0) : contextBudget;
        this.contextProperties = contextProperties == null
                ? new AgentScopeContextProperties(true, 0, 0, 0) : contextProperties;
        this.skillRunner = new AgentScopeSkillRunner(mapper, modelFactory,
                this.contextBudget, this.contextProperties);
    }

    @Override
    public String name() {
        return "agentscope";
    }

    /** 迭代 20C：Planner/Judge 无工具建议；原始 JSON 必须回到 Vatica 做机械校验。 */
    @Override
    public Optional<AdvisoryResult> advise(AdvisoryRequest request) {
        long start = System.nanoTime();
        return RequestIdentityContext.callWith(request.identity(), () -> {
            ModelSlot slot = request.modelSlot() == null
                    ? registry.activeSlotFor(request.kind() == AdvisoryKind.JUDGE
                            ? ModelSlot.CAP_JUDGE : ModelSlot.CAP_PLANNER)
                    : request.modelSlot();
            Toolkit toolkit = new Toolkit();
            String agentName = "vatica-advisory-"
                    + request.kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            Model model = modelFactory.apply(slot);
            ContextBudget.CallSite callSite = request.kind() == AdvisoryKind.JUDGE
                    ? ContextBudget.CallSite.JUDGE : ContextBudget.CallSite.PLANNER;
            AgentScopeContextBudgetMiddleware budgetMiddleware = contextMiddleware(
                    model, request.systemPrompt(), callSite);
            ReActAgent agent = ReActAgent.builder()
                    .name(agentName)
                    .sysPrompt(request.systemPrompt())
                    .model(model)
                    .toolkit(toolkit)
                    // 迭代 30B：Planner/Judge 建议调用也受模型窗口预算保护。
                    .middleware(budgetMiddleware)
                    .maxIters(1)
                    .defaultSessionId(request.sessionId())
                    .generateOptions(GenerateOptions.builder().reasoningEffort("high")
                            .toolChoice(new ToolChoice.Auto()).build())
                    .build();
            try {
                AgentReply reply = callAgent(agent, request.userPrompt(), request.identity(), request.sessionId());
                ChatUsage usage = reply.usage();
                StepUsage stepUsage = usage == null ? null : new StepUsage(
                        usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(),
                        usage.getCachedTokens());
                log.info("AgentScope advisory kind={} model={} tools={}",
                        request.kind(), slot.id(), toolkit.getToolSchemas().size());
                return Optional.of(new AdvisoryResult(reply.answer(),
                        (System.nanoTime() - start) / 1_000_000, stepUsage));
            } finally {
                agent.close();
            }
        });
    }

    /** 迭代 17A：生产任务步骤入口。工具已由 Vatica 完成权限、重试、Trace 与角色裁剪。 */
    @Override
    public StepResult executeStep(StepRequest request) {
        long start = System.nanoTime();
        return RequestIdentityContext.callWith(request.identity(), () -> {
            List<String> traces = new ArrayList<>();
            var role = request.agent() == null
                    ? agentRegistry.resolve(request.step().getAgent()) : request.agent();
            if (request.skill() != null) {
                return skillRunner.execute(request, role);
            }
            String system = """
                    你是 Vatica 执行 Agent。只执行当前步骤，只使用工具返回的数据，工具未返回的数据不得编造。
                    工具失败时如实说明原因，不得假装成功。身份、权限、审批与任务状态由 Vatica 管理。
                    完成后优先输出 JSON：{"result":"结果","notes":[],"needHelp":null,"discoveries":[]}。
                    needHelp 只用于确实无法继续的求助，discoveries 最多提出 2 个必要补充步骤。
                    """ + role.systemPrompt();
            ToolKitContext kit = buildToolkit(request.modelSlot(), request.tools(), traces,
                    "vatica-" + role.id(), system, Set.of(), request.sessionId());
            try {
                AgentReply reply = callAgent(kit.agent(), stepPrompt(request), request.identity(), request.sessionId());
                ChatUsage usage = reply.usage();
                StepUsage stepUsage = usage == null ? null : new StepUsage(
                        usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(),
                        usage.getCachedTokens());
                return new StepResult(reply.answer(), traces, (System.nanoTime() - start) / 1_000_000, stepUsage);
            } finally {
                kit.agent().close();
            }
        });
    }

    @Override
    public PovResult runSingleAgent(String goal, RequestIdentity identity, FilePermissionPolicy permission) {
        long start = System.nanoTime();
        return RequestIdentityContext.callWith(identity, () -> {
            List<String> traces = new ArrayList<>();
            ToolKitContext kit = buildToolkit(identity, permission, traces,
                    "vatica-poc", "你是 Vatica 执行 Agent。只使用工具返回的数据。", Set.of());
            try {
                String answer = callAgent(kit.agent(), goal, identity).answer();
                return new PovResult(answer, traces, (System.nanoTime() - start) / 1_000_000);
            } finally {
                kit.agent().close();
            }
        });
    }

    @Override
    public PovResult runDualAgentBlackboard(String goal, RequestIdentity identity,
            FilePermissionPolicy permission) {
        long start = System.nanoTime();
        return RequestIdentityContext.callWith(identity, () -> {
            List<String> traces = new ArrayList<>();
            // 工具门禁：document 角色只允许 text_stats；workspace 角色只允许 calculator（POC 可重复）
            ToolKitContext document = buildToolkit(identity, permission, traces,
                    "vatica-document", "你是文档 Agent，只使用 text_stats 工具。", Set.of("text_stats"));
            ToolKitContext workspace = buildToolkit(identity, permission, traces,
                    "vatica-workspace", "你是工作台 Agent，只使用 calculator 工具。", Set.of("calculator"));
            try {
                String note = callAgent(document.agent(), "分析并总结：\n" + goal, identity).answer();
                String summary = callAgent(workspace.agent(),
                        "原始目标：\n" + goal + "\n\n【黑板 note】\n" + note
                                + "\n\n请基于 note 给出最终结果，必要时调用 calculator。", identity).answer();
                return new PovResult(summary, traces, (System.nanoTime() - start) / 1_000_000);
            } finally {
                document.agent().close();
                workspace.agent().close();
            }
        });
    }

    /** POC 直调工具：验证 AgentScope Toolkit/AgentTool 链路真实执行。 */
    public String callTool(String toolName, String jsonInput, RequestIdentity identity,
            FilePermissionPolicy permission) {
        return RequestIdentityContext.callWith(identity, () -> {
            ToolKitContext kit = buildToolkit(identity, permission, new ArrayList<>(),
                    "vatica-tool-probe", "POC tool probe", Set.of(toolName));
            try {
                ToolUseBlock use = ToolUseBlock.builder()
                        .id("poc-" + UUID.randomUUID())
                        .name(toolName)
                        .input(readMap(jsonInput))
                        .content(jsonInput)
                        .build();
                ToolResultBlock result = kit.toolkit()
                        .callTool(ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build())
                        .block();
                return result == null || result.getOutput().isEmpty()
                        ? "" : result.getOutput().get(0).toString();
            } finally {
                kit.agent().close();
            }
        });
    }

    private AgentReply callAgent(ReActAgent agent, String message, RequestIdentity identity) {
        return callAgent(agent, message, identity, "poc-" + UUID.randomUUID());
    }

    private AgentReply callAgent(ReActAgent agent, String message, RequestIdentity identity, String sessionId) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(String.valueOf(identity.userId()))
                .sessionId(sessionId)
                .build();
        var reply = agent.call(java.util.List.of(new UserMessage(message)), context).block();
        return reply == null
                ? new AgentReply("", null)
                : new AgentReply(reply.getTextContent() == null ? "" : reply.getTextContent(), reply.getChatUsage());
    }

    private ToolKitContext buildToolkit(RequestIdentity identity, FilePermissionPolicy permission,
            List<String> traces, String agentName, String sysPrompt, Set<String> allowedTools) {
        ModelSlot slot = registry.defaultSlot();
        String channel = TenantChannels.chat(identity, "poc-agentscope");
        io.agentscope.core.tool.AgentTool[] tools = PermissionBoundToolCallbacks.wrap(
                vaticaTools, permission, channel, identity, null);
        return buildToolkit(slot, tools, traces,
                agentName, sysPrompt, allowedTools, "poc-session");
    }

    private ToolKitContext buildToolkit(ModelSlot slot, io.agentscope.core.tool.AgentTool[] tools, List<String> traces,
            String agentName, String sysPrompt, Set<String> allowedTools, String sessionId) {
        Model model = modelFactory.apply(slot);
        Toolkit toolkit = new Toolkit();
        toolkit.setChunkCallback((use, result) -> {
            String output = result.getOutput().isEmpty() ? "(empty)" : result.getOutput().get(0).toString();
            traces.add(agentName + ":" + use.getName() + " -> "
                    + TraceSanitizer.outputSummary(output, null) + " [" + result.getState() + "]");
        });
        AgentScopeToolGroupAdapter.Registration registration = AgentScopeToolGroupAdapter.register(
                toolkit, tools, allowedTools);
        List<String> registered = new ArrayList<>(registration.selectedToolNames());
        if (!registration.missingAllowedToolNames().isEmpty()) {
            log.warn("AgentScope agent={} requested unavailable tools={}",
                    agentName, registration.missingAllowedToolNames());
        }
        AgentScopeContextBudgetMiddleware budgetMiddleware = contextMiddleware(
                model, sysPrompt, ContextBudget.CallSite.EXECUTOR);
        GenerateOptions.Builder options = GenerateOptions.builder().reasoningEffort("none")
                .toolChoice(new ToolChoice.Auto());
        ReActAgent agent = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                // 迭代 30B：任务步骤和 POC Agent 的每轮模型调用统一走预算 middleware。
                .middleware(budgetMiddleware)
                .maxIters(8)
                .defaultSessionId(sessionId)
                .generateOptions(options.build())
                .build();
        log.info("AgentScope agent={} toolkit={} schemas={}",
                agentName, registered, toolkit.getToolSchemas().size());
        return new ToolKitContext(toolkit, agent);
    }

    private AgentScopeContextBudgetMiddleware contextMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite) {
        return new AgentScopeContextBudgetMiddleware(model, systemPrompt, callSite,
                contextBudget.tokensFor(callSite), contextProperties);
    }

    static String stepPrompt(StepRequest request) {
        StringBuilder prompt = new StringBuilder("任务目标：").append(request.goal()).append('\n');
        if (!request.context().isEmpty()) {
            prompt.append("依赖步骤结果与任务笔记（参考，不要重复执行）：\n");
            for (String item : request.context()) {
                prompt.append("- ").append(item).append('\n');
            }
        }
        if (request.reflectionFeedback() != null && !request.reflectionFeedback().isBlank()) {
            prompt.append("上一轮质量评测反馈：\n").append(request.reflectionFeedback())
                    .append("\n本轮必须针对性修复，但不得改变目标或扩大范围。\n");
        }
        return prompt.append("现在执行步骤（第 ").append(request.step().getId()).append(" 步）：")
                .append(request.step().getDescription())
                .append("\n完成后优先输出 JSON：{\"result\":\"结果\",\"notes\":[],\"needHelp\":null,\"discoveries\":[]}。")
                .append("若无协作信号也可返回纯文本结果。").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("操作失败：工具参数 JSON 非法。", e);
        }
    }

    private record ToolKitContext(Toolkit toolkit, ReActAgent agent) {
    }

    private record AgentReply(String answer, ChatUsage usage) {
    }
}
