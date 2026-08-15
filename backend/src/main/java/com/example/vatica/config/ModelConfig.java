package com.example.vatica.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 备用模型装配（迭代 7 I7-5）：通义千问（OpenAI 兼容）ChatClient，
 * 与主模型同构——本地工具 + MCP 远程工具合并进 defaultTools，工具循环 Advisor
 * 由 ChatClient.builder(model) 默认创建（DefaultChatClientBuilder 源码核实）。
 *
 * <p>两个关键决策（踩坑归档）：
 * <ul>
 *   <li><b>OpenAiChatModel 不注册为 Bean</b>：自动配置的 openAiChatModel 带
 *       {@code @ConditionalOnMissingBean}——一旦存在任何 OpenAiChatModel Bean，
 *       主模型（DeepSeek）会被跳过、全局误路由到备用模型（实测 401 定位）。
 *       故在 qwenChatClient 工厂方法内部构建模型对象，不进容器。</li>
 *   <li>apiKey 未配置时用占位符构建（构造不发起网络请求），路由层按 configured() 拦截。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfig {

    /** 备用模型对话客户端：模型在方法内部构建（不注册 Bean，避免挤掉主模型自动配置）。 */
    @Bean
    ChatClient qwenChatClient(ModelProperties props, ToolCallingManager toolCallingManager,
            ObjectProvider<MeterRegistry> meterRegistry, ToolCallbackProvider vaticaTools,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpToolProvider) {
        OpenAiChatModel qwenChatModel = buildQwenModel(props, toolCallingManager, meterRegistry.getIfUnique());
        SyncMcpToolCallbackProvider mcpTools = mcpToolProvider.getIfAvailable();
        ChatClient.Builder builder = ChatClient.builder(qwenChatModel);
        return mcpTools == null
                ? builder.defaultTools(vaticaTools).build()
                : builder.defaultTools(vaticaTools, mcpTools).build();
    }

    /** 构建方式照搬 Spring AI 2.0 自动配置（OpenAiChatAutoConfiguration 源码核实）。 */
    private static OpenAiChatModel buildQwenModel(ModelProperties props, ToolCallingManager toolCallingManager,
            MeterRegistry meters) {
        ModelProperties.Qwen q = props.qwen();
        String apiKey = q.configured() ? q.apiKey() : "not-configured";   // 占位符防客户端构造失败
        Duration timeout = Duration.ofSeconds(60);   // SDK 要求非空超时（与 spring.ai.openai 默认一致）
        OpenAIClient sync = OpenAiSetup.setupSyncClient(q.baseUrl(), apiKey, null, null, null, null,
                false, false, q.model(), timeout, 2, null, Map.of(), ObservationRegistry.NOOP, meters, List.of());
        OpenAIClientAsync async = OpenAiSetup.setupAsyncClient(q.baseUrl(), apiKey, null, null, null, null,
                false, false, q.model(), timeout, 2, null, Map.of(), ObservationRegistry.NOOP, meters, List.of());
        return OpenAiChatModel.builder()
                .openAiClient(sync)
                .openAiClientAsync(async)
                .options(OpenAiChatOptions.builder().model(q.model()).temperature(q.temperature()).build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(ObservationRegistry.NOOP)
                .meterRegistry(meters)
                .build();
    }
}
