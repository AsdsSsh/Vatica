package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.permission.FilePermissionContext;
import com.example.vatica.permission.FilePermissionMode;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.runtime.AgentRuntime;
import com.example.vatica.tool.TextTools;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代 15 I15-18：AgentScope 单 Agent POC 实测（仅 -Pagentscope profile 且显式提供
 * DEEPSEEK_POC_KEY 时运行）。调用 Vatica 真实工具（calculator）+ 真实 DeepSeek 槽位，
 * 验证 AgentScope 不绕过身份/权限/工具链。
 */
class AgentScopeSingleAgentPocTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "QWEN_POC_KEY", matches = ".+")
    void singleAgentCallsRealVaticaToolThroughAgentscope() {
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.defaultSlot()).thenReturn(new ModelSlot("qwen-poc", "Qwen POC",
                ModelSlot.PROTOCOL_OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1",
                System.getenv("QWEN_POC_KEY"), "qwen-plus", 0.7, true,
                List.of(ModelSlot.CAP_CHAT_FAST, ModelSlot.CAP_CHAT_REASON, ModelSlot.CAP_PLANNER,
                        ModelSlot.CAP_JUDGE, ModelSlot.CAP_SUMMARIZER), ""));

        ToolCallback calculator = calculatorCallback();
        ToolCallbackProvider tools = () -> new ToolCallback[] { calculator };
        AgentScopeRuntime runtime = new AgentScopeRuntime(registry, tools, new ObjectMapper());
        RequestIdentity identity = new RequestIdentity(1L, 1L, "LOCAL", "poc");
        FilePermissionPolicy permission = new FilePermissionPolicy(FilePermissionMode.READ_ONLY, List.of());

        AgentRuntime.PovResult result = runtime.runSingleAgent(
                "请回复一句：AgentScope POC 就绪。", identity, permission);

        assertThat(result.answer()).as("agent answer").isNotBlank();
        // 工具链验证走 AgentScope Toolkit 直调：Spring AI calculator 真实执行
        String toolResult = runtime.callTool("calculator",
                "{\"expression\":\"(3+4)*2\"}", identity, permission);
        assertThat(toolResult).contains("14");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "QWEN_POC_KEY", matches = ".+")
    void dualAgentBlackboardPocRunsWithRealModelAndGatedTools() {
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.defaultSlot()).thenReturn(new ModelSlot("qwen-poc", "Qwen POC",
                ModelSlot.PROTOCOL_OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1",
                System.getenv("QWEN_POC_KEY"), "qwen-plus", 0.7, true,
                List.of(ModelSlot.CAP_CHAT_FAST, ModelSlot.CAP_CHAT_REASON, ModelSlot.CAP_PLANNER,
                        ModelSlot.CAP_JUDGE, ModelSlot.CAP_SUMMARIZER), ""));
        ToolCallback calculator = calculatorCallback();
        ToolCallback textStats = new ToolCallback() {
            private final TextTools textTools = new TextTools();

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("text_stats")
                        .description("统计一段文本的字数、行数与段落数。")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                try {
                    return textTools.textStats(new ObjectMapper().readTree(toolInput).path("text").asText());
                } catch (Exception e) {
                    throw new IllegalArgumentException("工具参数错误：" + e.getMessage(), e);
                }
            }
        };
        ToolCallbackProvider tools = () -> new ToolCallback[] { calculator, textStats };
        AgentScopeRuntime runtime = new AgentScopeRuntime(registry, tools, new ObjectMapper());
        RequestIdentity identity = new RequestIdentity(1L, 1L, "LOCAL", "poc");
        FilePermissionPolicy permission = new FilePermissionPolicy(FilePermissionMode.READ_ONLY, List.of());

        AgentRuntime.PovResult result = runtime.runDualAgentBlackboard(
                "先统计“迭代 15 完成”这段文本，再计算 8*9，最终输出两个结果。",
                identity, permission);

        assertThat(result.answer()).as("dual agent answer").isNotBlank();
        assertThat(runtime.callTool("text_stats", "{\"text\":\"你好 世界\"}", identity, permission))
                .contains("4");
        assertThat(runtime.callTool("calculator", "{\"expression\":\"8*9\"}", identity, permission))
                .contains("72");
    }

    private static ToolCallback calculatorCallback() {
        TextTools textTools = new TextTools();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("calculator")
                        .description("计算数学表达式并返回数值结果。")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                try {
                    return textTools.calculate(new ObjectMapper().readTree(toolInput).path("expression").asText());
                } catch (Exception e) {
                    throw new IllegalArgumentException("工具参数错误：" + e.getMessage(), e);
                }
            }
        };
    }

    /** 迭代 15 I15-21：AgentScope 工具执行不绕过 Vatica 身份快照与权限上下文。 */
    @Test
    void agentscopeToolExecutionKeepsVaticaIdentityAndPermissionContext() {
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.defaultSlot()).thenReturn(new ModelSlot("qwen-poc", "Qwen POC",
                ModelSlot.PROTOCOL_OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "", "qwen-plus", 0.7, true, List.of(ModelSlot.CAP_CHAT_FAST), ""));
        var seen = new java.util.concurrent.atomic.AtomicReference<RequestIdentity>();
        var permissionSeen = new java.util.concurrent.atomic.AtomicReference<FilePermissionMode>();
        ToolCallback boundaryProbe = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("boundary_probe").description("probe")
                        .inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                seen.set(com.example.vatica.auth.RequestIdentityContext.current());
                permissionSeen.set(FilePermissionContext.current().policy().mode());
                return "ok";
            }
        };
        AgentScopeRuntime runtime = new AgentScopeRuntime(registry, () -> new ToolCallback[] { boundaryProbe },
                new ObjectMapper());
        RequestIdentity identity = new RequestIdentity(7L, 9L, "MEMBER", "alice");
        FilePermissionPolicy permission = new FilePermissionPolicy(FilePermissionMode.READ_ONLY, List.of());

        String result = runtime.callTool("boundary_probe", "{}", identity, permission);

        assertThat(result).contains("ok");
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().userId()).isEqualTo(7L);
        assertThat(permissionSeen.get()).isEqualTo(FilePermissionMode.READ_ONLY);
    }
}
