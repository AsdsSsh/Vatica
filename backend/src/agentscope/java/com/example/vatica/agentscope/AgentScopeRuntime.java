package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionBoundToolCallbacks;
import com.example.vatica.runtime.AgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
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

    public AgentScopeRuntime(ModelRegistry registry, ToolCallbackProvider vaticaTools, ObjectMapper mapper) {
        this.registry = registry;
        this.vaticaTools = vaticaTools;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "agentscope";
    }

    @Override
    public PovResult runSingleAgent(String goal, RequestIdentity identity, FilePermissionPolicy permission) {
        long start = System.nanoTime();
        return RequestIdentityContext.callWith(identity, () -> {
            List<String> traces = new ArrayList<>();
            ToolKitContext kit = buildToolkit(identity, permission, traces,
                    "vatica-poc", "你是 Vatica 执行 Agent。只使用工具返回的数据。", Set.of());
            try {
                String answer = callAgent(kit.agent(), goal, identity);
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
                String note = callAgent(document.agent(), "分析并总结：\n" + goal, identity);
                String summary = callAgent(workspace.agent(),
                        "原始目标：\n" + goal + "\n\n【黑板 note】\n" + note
                                + "\n\n请基于 note 给出最终结果，必要时调用 calculator。", identity);
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

    private String callAgent(ReActAgent agent, String message, RequestIdentity identity) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(String.valueOf(identity.userId()))
                .sessionId("poc-" + UUID.randomUUID())
                .build();
        var reply = agent.call(java.util.List.of(new UserMessage(message)), context).block();
        return reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
    }

    private ToolKitContext buildToolkit(RequestIdentity identity, FilePermissionPolicy permission,
            List<String> traces, String agentName, String sysPrompt, Set<String> allowedTools) {
        ModelSlot slot = registry.defaultSlot();
        Model model = buildModel(slot);
        Toolkit toolkit = new Toolkit();
        toolkit.setChunkCallback((use, result) -> traces.add(agentName + ":" + use.getName()
                + " -> " + (result.getOutput().isEmpty() ? "(empty)" : result.getOutput().get(0).toString())
                + " [" + result.getState() + "]"));
        String channel = TenantChannels.chat(identity, "poc-agentscope");
        ToolCallback[] callbacks = PermissionBoundToolCallbacks.wrap(
                vaticaTools, permission, channel, identity, null);
        List<String> registered = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            String toolName = callback.getToolDefinition().name();
            if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
                continue;
            }
            toolkit.registerAgentTool(new SpringAiToolAdapter(callback, mapper));
            registered.add(toolName);
        }
        GenerateOptions.Builder options = GenerateOptions.builder().reasoningEffort("none");
        if (registered.size() == 1) {
            options.toolChoice(new ToolChoice.Specific(registered.get(0)));
        } else {
            options.toolChoice(new ToolChoice.Auto());
        }
        ReActAgent agent = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(5)
                .defaultSessionId("poc-session")
                .generateOptions(options.build())
                .build();
        log.info("AgentScope POC agent={} toolkit={} schemas={}",
                agentName, registered, toolkit.getToolSchemas().size());
        return new ToolKitContext(toolkit, agent);
    }

    private Model buildModel(ModelSlot slot) {
        if (!ModelSlot.PROTOCOL_OPENAI.equals(slot.protocol())) {
            throw new IllegalArgumentException("操作失败：AgentScope POC 当前仅支持 OpenAI 兼容协议槽位。");
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
}
