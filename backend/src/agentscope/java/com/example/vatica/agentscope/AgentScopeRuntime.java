package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionBoundToolCallbacks;
import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.runtime.AgentRuntime;
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
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * 迭代 15 I15-18/I15-20：AgentScope 单/双 Agent POC（仅 -Pagentscope 构建加载）。
 * 使用 Vatica 的真实模型槽位/API Key、工具回调、身份快照与权限快照；
 * AgentScope 只负责 ReAct 循环，不建立第二套业务状态机。
 */
public class AgentScopeRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeRuntime.class);

    private final ModelRegistry registry;
    private final ToolCallbackProvider vaticaTools;
    private final ObjectMapper mapper;
    private final AgentRegistry agentRegistry;
    private final Function<ModelSlot, Model> modelFactory;

    public AgentScopeRuntime(ModelRegistry registry, ToolCallbackProvider vaticaTools, ObjectMapper mapper) {
        this(registry, vaticaTools, mapper, new AgentRegistry());
    }

    public AgentScopeRuntime(ModelRegistry registry, ToolCallbackProvider vaticaTools, ObjectMapper mapper,
            AgentRegistry agentRegistry) {
        this(registry, vaticaTools, mapper, agentRegistry, AgentScopeRuntime::buildModel);
    }

    AgentScopeRuntime(ModelRegistry registry, ToolCallbackProvider vaticaTools, ObjectMapper mapper,
            AgentRegistry agentRegistry, Function<ModelSlot, Model> modelFactory) {
        this.registry = registry;
        this.vaticaTools = vaticaTools;
        this.mapper = mapper;
        this.agentRegistry = agentRegistry;
        this.modelFactory = modelFactory;
    }

    @Override
    public String name() {
        return "agentscope";
    }

    /** 迭代 17A：生产任务步骤入口。工具已由 Vatica 完成权限、重试、Trace 与角色裁剪。 */
    @Override
    public StepResult executeStep(StepRequest request) {
        long start = System.nanoTime();
        return RequestIdentityContext.callWith(request.identity(), () -> {
            List<String> traces = new ArrayList<>();
            var role = request.agent() == null
                    ? agentRegistry.resolve(request.step().getAgent()) : request.agent();
            String system = """
                    你是 Vatica 执行 Agent。只执行当前步骤，只使用工具返回的数据，工具未返回的数据不得编造。
                    工具失败时如实说明原因，不得假装成功。身份、权限、审批与任务状态由 Vatica 管理。
                    完成后优先输出 JSON：{"result":"结果","notes":[],"needHelp":null,"discoveries":[]}。
                    needHelp 只用于确实无法继续的求助，discoveries 最多提出 2 个必要补充步骤。
                    """ + role.systemPrompt();
            ToolKitContext kit = buildToolkit(request.modelSlot(), request.toolCallbacks(), traces,
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

    /** POC 直调工具：验证 Spring AI ToolCallback 经 AgentScope Toolkit/AgentTool 适配后真实执行。 */
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
        ToolCallback[] callbacks = PermissionBoundToolCallbacks.wrap(
                vaticaTools, permission, channel, identity, null);
        return buildToolkit(slot, callbacks, traces, agentName, sysPrompt, allowedTools, "poc-session");
    }

    private ToolKitContext buildToolkit(ModelSlot slot, ToolCallback[] callbacks, List<String> traces,
            String agentName, String sysPrompt, Set<String> allowedTools, String sessionId) {
        Model model = modelFactory.apply(slot);
        Toolkit toolkit = new Toolkit();
        toolkit.setChunkCallback((use, result) -> {
            String output = result.getOutput().isEmpty() ? "(empty)" : result.getOutput().get(0).toString();
            traces.add(agentName + ":" + use.getName() + " -> "
                    + TraceSanitizer.outputSummary(output, null) + " [" + result.getState() + "]");
        });
        List<String> registered = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            String toolName = callback.getToolDefinition().name();
            if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
                continue;
            }
            toolkit.registerAgentTool(new SpringAiToolAdapter(callback, mapper));
            registered.add(toolName);
        }
        GenerateOptions.Builder options = GenerateOptions.builder().reasoningEffort("none")
                .toolChoice(new ToolChoice.Auto());
        ReActAgent agent = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(8)
                .defaultSessionId(sessionId)
                .generateOptions(options.build())
                .build();
        log.info("AgentScope agent={} toolkit={} schemas={}",
                agentName, registered, toolkit.getToolSchemas().size());
        return new ToolKitContext(toolkit, agent);
    }

    private static Model buildModel(ModelSlot slot) {
        if (!ModelSlot.PROTOCOL_OPENAI.equals(slot.protocol())) {
            throw new IllegalArgumentException("操作失败：AgentScope 当前仅支持 OpenAI 兼容协议槽位；"
                    + "可临时设置 VATICA_AGENT_RUNTIME=legacy 使用 Anthropic 槽位。");
        }
        boolean deepseek = slot.baseUrl() != null
                && slot.baseUrl().toLowerCase(java.util.Locale.ROOT).contains("deepseek");
        return OpenAIChatModel.builder()
                .apiKey(slot.apiKey() == null ? "" : slot.apiKey())
                .baseUrl(slot.baseUrl())
                .modelName(slot.model())
                .stream(false)
                .formatter(deepseek ? new DeepSeekFormatter() : new OpenAIChatFormatter())
                .contextWindowSize(16_000)
                .build();
    }

    private static String stepPrompt(StepRequest request) {
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
