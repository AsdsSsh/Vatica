package com.example.vatica.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.anthropic.models.messages.Model;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 动态模型注册表（迭代 8.5 模型配置中心）：按槽位配置构建 ChatModel/ChatClient，
 * 对话（带工具 + MCP 韧性包装）、任务执行、规划、评测统一从这里取默认模型——
 * 界面保存配置后即时生效（按配置指纹缓存，配置变化自动重建）。
 *
 * <p>两个关键决策（继承迭代 7 踩坑归档）：
 * <ul>
 *   <li><b>动态模型不进容器</b>：迭代 7 已实证——任何 OpenAiChatModel Bean 都会触发
 *       {@code @ConditionalOnMissingBean} 挤掉主模型自动配置。这里全部在方法内构建、不进容器。</li>
 *   <li><b>超时显式传</b>：OpenAI/Anthropic SDK 的 setup 对 null 超时抛"Parameter specified
 *       as non-null is null"（迭代 7 实测），统一 {@code Duration.ofSeconds(60)}。</li>
 * </ul>
 */
@Component
public class ModelRegistry {

    private final ModelConfigService config;
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpToolProvider;
    private final ToolCallingManager toolCallingManager;

    /** 客户端缓存：key = slotId + 配置指纹。 */
    private final ConcurrentHashMap<String, ChatClient> clients = new ConcurrentHashMap<>();

    public ModelRegistry(ModelConfigService config,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpToolProvider, ToolCallingManager toolCallingManager) {
        this.config = config;
        this.mcpToolProvider = mcpToolProvider;
        this.toolCallingManager = toolCallingManager;
    }

    /** 当前生效的槽位列表（模型选择器/设置界面数据源）。 */
    public List<ModelSlot> slots() {
        return config.slots();
    }

    /** 默认模型 = 第一个启用的槽位（任务执行/规划/评测共用）。 */
    public ModelSlot defaultSlot() {
        return config.slots().stream()
                .filter(ModelSlot::enabled)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：没有启用的模型，请先在设置中配置。"));
    }

    /** 按 id 取带工具的对话/执行客户端；id 为空取默认。 */
    public ChatClient clientFor(String id) {
        if (id == null || id.isBlank()) {
            return defaultClient();
        }
        ModelSlot slot = config.slots().stream()
                .filter(s -> s.id().equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作失败：未知模型（" + id + "）。"));
        if (!slot.enabled()) {
            throw new IllegalArgumentException("操作失败：模型未启用（" + slot.name() + "），请在设置中启用。");
        }
        return cached(slot, true);
    }

    /** 默认模型对话/执行客户端（带工具）。 */
    public ChatClient defaultClient() {
        return clientFor(defaultSlot().id());
    }

    /** 规划专用客户端：无工具（规划只分解不执行）。 */
    public ChatClient plannerClient() {
        return cached(defaultSlot(), false);
    }

    /** 评测专用客户端：无工具（评测只读材料）。 */
    public ChatClient judgeClient() {
        return cached(defaultSlot(), false);
    }

    /** 连通性测试（不带工具）：发一句最短指令，成功即返回模型回复。 */
    public String testConnection(ModelSlot slot) {
        ChatClient client = ChatClient.builder(buildModel(slot)).build();
        return client.prompt("请只回复两个字：正常").call().content();
    }

    private ChatClient cached(ModelSlot slot, boolean withTools) {
        // 迭代 10 I10-2：缓存键必须包含 withTools——对话（带工具）与规划/评测（无工具）
        // 是同槽位不同职责的客户端，旧键只有 slotId+fingerprint，先构建者会被另一方复用
        String id = slot.id().toLowerCase(Locale.ROOT);
        String key = id + "|" + withTools + "|" + slot.fingerprint();
        ChatClient existing = clients.get(key);
        if (existing != null) {
            return existing;
        }
        // 迭代 10 I10-9：同槽位同职责配置变更后清理旧指纹缓存，避免长跑缓存无限增长
        // （前缀带 withTools：只清自己这一列，不能把对话/规划评测的另一列误删）
        String prefix = id + "|" + withTools + "|";
        clients.keySet().removeIf(k -> k.startsWith(prefix) && !k.equals(key));
        return clients.computeIfAbsent(key, k -> build(slot, withTools));
    }

    private ChatClient build(ModelSlot slot, boolean withTools) {
        ChatClient.Builder builder = ChatClient.builder(buildModel(slot));
        if (withTools) {
            // 迭代 12 热修：本地工具由 ChatController/TaskService 按请求用
            // PermissionBoundToolCallbacks/ToolActivityCallbacks 注入；
            // 这里只注册 MCP 远程工具兜底——若再注册 vaticaTools 会与请求级
            // ToolCallback[] 叠加成同名重复，触发 ToolCallingChatOptions 校验异常。
            SyncMcpToolCallbackProvider mcpTools = mcpToolProvider.getIfAvailable();
            if (mcpTools != null) {
                builder.defaultTools(new McpToolProviderGuard(mcpTools));
            }
        }
        return builder.build();
    }

    /** 按协议构建 ChatModel（不进容器，见类注释）。 */
    public ChatModel buildModel(ModelSlot slot) {
        return switch (slot.protocol()) {
            case ModelSlot.PROTOCOL_OPENAI -> buildOpenAi(slot);
            case ModelSlot.PROTOCOL_ANTHROPIC -> buildAnthropic(slot);
            default -> throw new IllegalArgumentException("操作失败：不支持的协议（" + slot.protocol() + "）。");
        };
    }

    private ChatModel buildOpenAi(ModelSlot slot) {
        String apiKey = slot.apiKey() == null ? "" : slot.apiKey();
        Duration timeout = Duration.ofSeconds(60);   // SDK 要求非空超时（迭代 7 实测）
        OpenAIClient sync = OpenAiSetup.setupSyncClient(blankToNull(slot.baseUrl()), apiKey, null, null, null,
                null, false, false, slot.model(), timeout, 2, null, Map.of(), ObservationRegistry.NOOP, null,
                List.of());
        OpenAIClientAsync async = OpenAiSetup.setupAsyncClient(blankToNull(slot.baseUrl()), apiKey, null, null, null,
                null, false, false, slot.model(), timeout, 2, null, Map.of(), ObservationRegistry.NOOP, null,
                List.of());
        return OpenAiChatModel.builder()
                .openAiClient(sync)
                .openAiClientAsync(async)
                .options(OpenAiChatOptions.builder().model(slot.model()).temperature(slot.temperature()).build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private ChatModel buildAnthropic(ModelSlot slot) {
        return AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder()
                        .apiKey(blankToNull(slot.apiKey()))
                        .baseUrl(blankToNull(slot.baseUrl()))
                        .model(Model.Companion.of(slot.model()))
                        .temperature(slot.temperature())
                        .timeout(Duration.ofSeconds(60))
                        .build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
