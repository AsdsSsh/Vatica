package com.example.vatica.runtime;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionBoundToolCallbacks;
import com.example.vatica.trace.TraceSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代 15 I15-17：LegacyRuntime = 现有 Spring AI 工具循环的 POC 包装。
 * 真实走 Vatica 身份/权限快照/工具链；trace 只做内存收集（不落 agent_trace）。
 */
public class LegacyRuntime implements AgentRuntime {

    private final ModelRegistry registry;
    private final ToolCallbackProvider vaticaTools;
    private final ObjectMapper mapper;

    public LegacyRuntime(ModelRegistry registry, ToolCallbackProvider vaticaTools, ObjectMapper mapper) {
        this.registry = registry;
        this.vaticaTools = vaticaTools;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "legacy";
    }

    @Override
    public PovResult runSingleAgent(String goal, RequestIdentity identity, FilePermissionPolicy permission) {
        long start = System.nanoTime();
        return RequestIdentityContext.callWith(identity, () -> {
            String channel = TenantChannels.chat(identity, "poc-legacy");
            ToolCallback[] callbacks = PermissionBoundToolCallbacks.wrap(
                    vaticaTools, permission, channel, identity, null);
            List<String> traces = new ArrayList<>();
            callbacks = CollectingToolCallbacks.wrap(callbacks, mapper, traces);
            String answer = registry.defaultClient().prompt()
                    .system("你是 Vatica 执行 Agent。只使用工具返回的数据；未授权路径会自动触发用户授权。")
                    .user(goal)
                    .toolCallbacks(callbacks)
                    .call()
                    .content();
            return new PovResult(answer == null ? "" : answer, traces,
                    (System.nanoTime() - start) / 1_000_000);
        });
    }

    @Override
    public PovResult runDualAgentBlackboard(String goal, RequestIdentity identity,
            FilePermissionPolicy permission) {
        long start = System.nanoTime();
        PovResult first = runSingleAgent("你是文档角色。阅读/整理与目标相关的信息：\n" + goal,
                identity, permission);
        PovResult second = runSingleAgent("你是工作台角色。基于黑板 note 完成目标：\n"
                + "【黑板 note】\n" + first.answer() + "\n\n原始目标：\n" + goal, identity, permission);
        List<String> traces = new ArrayList<>(first.toolTraces());
        traces.addAll(second.toolTraces());
        return new PovResult(second.answer() == null ? "" : second.answer(), traces,
                (System.nanoTime() - start) / 1_000_000);
    }

    /** 内存 trace 收集器（POC 用，不持久化、不推 SSE）。 */
    private static final class CollectingToolCallbacks {
        private CollectingToolCallbacks() {
        }

        static ToolCallback[] wrap(ToolCallback[] callbacks, ObjectMapper mapper, List<String> traces) {
            ToolCallback[] wrapped = new ToolCallback[callbacks.length];
            for (int i = 0; i < callbacks.length; i++) {
                ToolCallback delegate = callbacks[i];
                wrapped[i] = new ToolCallback() {
                    @Override
                    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                        return delegate.getToolDefinition();
                    }

                    @Override
                    public String call(String toolInput) {
                        String tool = delegate.getToolDefinition().name();
                        long start = System.nanoTime();
                        try {
                            String out = delegate.call(toolInput);
                            traces.add(tool + " -> " + TraceSanitizer.outputSummary(out, null)
                                    + " (" + (System.nanoTime() - start) / 1_000_000 + "ms)");
                            return out;
                        } catch (RuntimeException e) {
                            traces.add(tool + " FAILED: " + e.getMessage());
                            throw e;
                        }
                    }
                };
            }
            return wrapped;
        }
    }
}
