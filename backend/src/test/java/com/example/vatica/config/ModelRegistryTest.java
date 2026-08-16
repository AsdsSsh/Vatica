package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

/** 动态模型注册表单测（迭代 8.5）：双协议构建 / 路由与快速失败 / 配置指纹缓存。 */
class ModelRegistryTest {

    @TempDir
    Path tempDir;

    private ModelConfigService config;
    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        config = new ModelConfigService(
                new AppStateProperties(tempDir.toString()),
                new ObjectMapper(),
                new OpenAiDefaultsProperties("deep-key", "https://api.deepseek.com",
                        new OpenAiDefaultsProperties.Chat("deepseek-v4-flash", 0.7)),
                new ModelProperties(new ModelProperties.Qwen("", "", "", null)));
        registry = new ModelRegistry(config,
                mcpProvider(null),
                ToolCallingManager.builder().build());
    }

    /** OpenAI 兼容协议 → OpenAiChatModel。 */
    @Test
    void buildsOpenAiModel() {
        ModelSlot slot = new ModelSlot("ds", "DeepSeek", ModelSlot.PROTOCOL_OPENAI,
                "https://api.deepseek.com", "k", "deepseek-v4-flash", 0.7, true);
        assertThat(registry.buildModel(slot)).isInstanceOf(OpenAiChatModel.class);
    }

    /** Anthropic 协议 → AnthropicChatModel（离线构建，不发起网络）。 */
    @Test
    void buildsAnthropicModel() {
        ModelSlot slot = new ModelSlot("claude", "Claude", ModelSlot.PROTOCOL_ANTHROPIC,
                "https://api.anthropic.com", "ant-key", "claude-sonnet-4-6", 0.3, true);
        assertThat(registry.buildModel(slot)).isInstanceOf(AnthropicChatModel.class);
    }

    /** 未知 id / 未启用槽位 → 快速失败（消息面向用户）。 */
    @Test
    void routingFailsFast() {
        assertThatThrownBy(() -> registry.clientFor("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知模型");
        assertThatThrownBy(() -> registry.clientFor("qwen"))   // 默认槽位 qwen 无 key 未启用
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未启用");
    }

    /** 默认客户端 = 第一个启用槽位；与按 id 取到的客户端同一实例（缓存命中）。 */
    @Test
    void defaultClientIsFirstEnabledAndCached() {
        ChatClient byDefault = registry.defaultClient();
        ChatClient byId = registry.clientFor("deepseek");

        assertThat(byId).isSameAs(byDefault);
    }

    /**
     * 迭代 10 I10-2 回归：对话客户端（带工具）与规划/评测客户端（无工具）
     * 必须缓存隔离——旧实现缓存键不含 withTools，先构建者会被另一方复用。
     * 规划与评测同为无工具客户端，共享同一实例是允许的（职责相同）。
     */
    @Test
    void toolModeIsPartOfCacheKey() {
        ChatClient chat = registry.defaultClient();
        ChatClient planner = registry.plannerClient();
        ChatClient judge = registry.judgeClient();

        assertThat(chat).isNotSameAs(planner);
        assertThat(chat).isNotSameAs(judge);
        assertThat(planner).isSameAs(judge);

        // 各自按自己的模式缓存：再次取到的是同一实例
        assertThat(registry.defaultClient()).isSameAs(chat);
        assertThat(registry.plannerClient()).isSameAs(planner);
        assertThat(registry.judgeClient()).isSameAs(judge);
    }

    /** 配置指纹缓存：同配置复用实例；配置变化（温度不同）→ 重建新实例。 */
    @Test
    void cacheRebuildsWhenConfigurationChanges() {
        ChatClient first = registry.clientFor("deepseek");
        ChatClient second = registry.clientFor("deepseek");
        assertThat(first).isSameAs(second);

        // 界面保存了新配置（温度 0.9）→ 指纹变化 → 同 id 重建客户端
        config.save(List.of(new ModelSlot("deepseek", "DeepSeek v4", ModelSlot.PROTOCOL_OPENAI,
                "https://api.deepseek.com", "deep-key", "deepseek-v4-flash", 0.9, true)));

        assertThat(registry.clientFor("deepseek")).isNotSameAs(first);
    }

    private static ObjectProvider<SyncMcpToolCallbackProvider> mcpProvider(
            SyncMcpToolCallbackProvider provider) {
        return new ObjectProvider<>() {
            @Override
            public SyncMcpToolCallbackProvider getObject() {
                return provider;
            }

            @Override
            public SyncMcpToolCallbackProvider getObject(Object... args) {
                return provider;
            }

            @Override
            public SyncMcpToolCallbackProvider getIfAvailable() {
                return provider;
            }

            @Override
            public SyncMcpToolCallbackProvider getIfUnique() {
                return provider;
            }
        };
    }
}
