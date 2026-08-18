package com.example.vatica.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.anthropic.models.messages.Model;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.example.vatica.usage.UsageAdvisor;

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
    private final ModelCredentialStore credentials;
    private final UserModelService userModels;
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpToolProvider;
    private final ToolCallingManager toolCallingManager;
    private final UsageAdvisor usageAdvisor;

    /** 客户端缓存：key = slotId + 配置指纹。 */
    private final ConcurrentHashMap<String, ChatClient> clients = new ConcurrentHashMap<>();

    /** 迭代 15 I15-5：每个角色在同能力槽位列表中的故障转移偏移（401/超时后推进）。 */
    private final ConcurrentHashMap<String, AtomicInteger> roleOffsets = new ConcurrentHashMap<>();

    /** 角色故障转移客户端缓存：key = 能力|工具|深思档位（内部每次 prompt 动态取偏移后的槽位）。 */
    private final ConcurrentHashMap<String, RoleFailoverChatClient> roleClients = new ConcurrentHashMap<>();

    public ModelRegistry(ModelConfigService config, ModelCredentialStore credentials, UserModelService userModels,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpToolProvider, ToolCallingManager toolCallingManager,
            UsageAdvisor usageAdvisor) {
        this.config = config;
        this.credentials = credentials;
        this.userModels = userModels;
        this.mcpToolProvider = mcpToolProvider;
        this.toolCallingManager = toolCallingManager;
        this.usageAdvisor = usageAdvisor;
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
        return cached(slot, true, ReasoningMode.DISABLED);
    }

    /** 默认模型对话客户端（带工具；迭代 15 起默认关闭深思 = 快通道）。 */
    public ChatClient defaultClient() {
        return cached(defaultSlot(), true, ReasoningMode.DISABLED);
    }

    /** 迭代 15 I15-4：执行客户端——带工具 + LOW 深思；I15-5 执行角色复用 chat-reason 能力槽位。 */
    public ChatClient executorClient() {
        return roleFailoverClient(ModelSlot.CAP_CHAT_REASON, true, ReasoningMode.LOW);
    }

    /** 迭代 17A：任务工具全部按请求注入，客户端不再携带可绕过角色门禁的默认 MCP 工具。 */
    public ChatClient taskExecutorClient() {
        return roleFailoverClient(ModelSlot.CAP_CHAT_REASON, false, ReasoningMode.LOW);
    }

    /** 迭代 17C：按 Agent 模型绑定解析出的槽位构建无默认工具客户端。 */
    public ChatClient taskClientFor(ModelSlot slot) {
        if (slot == null || !slot.enabled()) {
            throw new IllegalArgumentException("操作失败：Agent 绑定的模型槽位不可用。");
        }
        return cached(slot, false, ReasoningMode.LOW);
    }

    /** 规划专用客户端：无工具 + HIGH 深思（规划只分解不执行）。 */
    public ChatClient plannerClient() {
        return roleFailoverClient(ModelSlot.CAP_PLANNER, false, ReasoningMode.HIGH);
    }

    /** 评测专用客户端：无工具 + HIGH 深思（评测只读材料）。 */
    public ChatClient judgeClient() {
        return roleFailoverClient(ModelSlot.CAP_JUDGE, false, ReasoningMode.HIGH);
    }

    /** 迭代 15 I15-9：摘要专用客户端——无工具 + 关闭深思（摘要只压缩事实）。 */
    public ChatClient summarizerClient() {
        return roleFailoverClient(ModelSlot.CAP_SUMMARIZER, false, ReasoningMode.DISABLED);
    }

    /** 迭代 13 I13-5：请求级临时凭据客户端——每次新建，不查库、不写库、不进缓存。 */
    public ChatClient ephemeralClient(EphemeralCredential credential, boolean withTools) {
        return ephemeralClient(credential, withTools, ReasoningMode.DISABLED);
    }

    /** 迭代 15：任务角色使用临时凭据时按角色选深思档位。 */
    public ChatClient ephemeralClient(EphemeralCredential credential, boolean withTools, ReasoningMode mode) {
        return build(credential.toSlot(), withTools, mode);
    }

    /** 迭代 13 I13-4：用户自配槽位客户端（仅 ENCRYPTED_AT_REST；EPHEMERAL 需请求带 credential）。 */
    public ChatClient userClient(Long ownerId, String slotId, boolean withTools) {
        return userClient(ownerId, slotId, withTools, ReasoningMode.DISABLED);
    }

    /** 迭代 15：用户槽位也可按角色选深思档位。 */
    public ChatClient userClient(Long ownerId, String slotId, boolean withTools, ReasoningMode mode) {
        UserModelSlot slot = userModels.resolveSlot(ownerId, slotId);
        if (UserModelSlot.MODE_EPHEMERAL.equals(slot.getCredentialMode())) {
            throw new IllegalArgumentException("操作失败：该模型为仅本机模式，请随请求提供 credential 后重试。");
        }
        String apiKey = userModels.resolveApiKey(ownerId, slotId);
        ModelSlot model = new ModelSlot("user:" + slotId, slot.getName(), slot.getProtocol(), slot.getBaseUrl(),
                apiKey, slot.getModel(), slot.getTemperature(), true);
        return build(model, withTools, mode);
    }

    /** 迭代 15 I15-4：聊天“深思”开关——按当前模型槽位生成请求级 options（平台模型）。 */
    public ChatOptions.Builder<?> reasoningOptions(String modelId, EphemeralCredential credential,
            ReasoningMode mode) {
        ModelSlot slot = credential != null ? credential.toSlot() : slotFor(modelId);
        return ReasoningOptionsApplier.builder(slot, mode);
    }

    /** 用户自配槽位的深思 options（聊天选择器选中 user: 槽位时用）。 */
    public ChatOptions.Builder<?> reasoningOptionsForUser(Long ownerId, String slotId, ReasoningMode mode) {
        UserModelSlot slot = userModels.resolveSlot(ownerId, slotId);
        String apiKey = userModels.resolveApiKey(ownerId, slotId);
        ModelSlot model = new ModelSlot("user:" + slotId, slot.getName(), slot.getProtocol(), slot.getBaseUrl(),
                apiKey, slot.getModel(), slot.getTemperature(), true);
        return ReasoningOptionsApplier.builder(model, mode);
    }

    private ModelSlot slotFor(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return defaultSlot();
        }
        return config.slots().stream()
                .filter(s -> s.id().equalsIgnoreCase(modelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作失败：未知模型（" + modelId + "）。"));
    }

    /** 迭代 15 I15-5：某能力的候选槽位（启用且带标签）；没有标签时回退默认槽位（旧配置兼容）。 */
    List<ModelSlot> slotsForRole(String capability) {
        List<ModelSlot> matching = config.slots().stream()
                .filter(ModelSlot::enabled)
                .filter(s -> s.capabilities().contains(capability))
                .toList();
        return matching.isEmpty() ? List.of(defaultSlot()) : matching;
    }

    /** 迭代 17A：AgentScope 按执行角色取得当前生效槽位，沿用既有能力标签与故障转移偏移。 */
    public ModelSlot activeSlotFor(String capability) {
        List<ModelSlot> slots = slotsForRole(capability);
        return slots.get(currentRoleOffset(capability) % slots.size());
    }

    /**
     * 迭代 15 I15-5：角色客户端 = 每次 prompt 按当前偏移取同能力槽位；
     * 401/超时等可转移错误发生时推进偏移，下一请求自动切备用槽位。
     */
    private ChatClient roleFailoverClient(String capability, boolean withTools, ReasoningMode mode) {
        String key = capability + "|" + withTools + "|" + mode;
        return roleClients.computeIfAbsent(key, k -> {
            List<ModelSlot> slots = slotsForRole(capability);
            return new RoleFailoverChatClient(
                    () -> cached(slots.get(currentRoleOffset(capability) % slots.size()), withTools, mode),
                    ModelRegistry::isFailoverError,
                    () -> roleOffsets.computeIfAbsent(capability, k2 -> new AtomicInteger()).incrementAndGet());
        });
    }

    private int currentRoleOffset(String capability) {
        return Math.floorMod(roleOffsets.computeIfAbsent(capability, k -> new AtomicInteger()).get(), 1000);
    }

    int roleOffset(String capability) {
        return roleOffsets.getOrDefault(capability, new AtomicInteger()).get();
    }

    private static boolean isFailoverError(RuntimeException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("401") || message.contains("unauthorized")
                || message.contains("timeout") || message.contains("timed out");
    }

    /** 连通性测试（不带工具）：发一句最短指令，成功即返回模型回复。
     *  迭代 13：请求体 apiKey 为空时回退密文库中已保存的 key。 */
    public String testConnection(ModelSlot slot) {
        ChatClient client = ChatClient.builder(buildModel(withStoredKey(slot))).build();
        return client.prompt("请只回复两个字：正常").call().content();
    }

    private ChatClient cached(ModelSlot slot, boolean withTools, ReasoningMode reasoningMode) {
        // 迭代 10 I10-2：缓存键必须包含 withTools——对话（带工具）与规划/评测（无工具）
        // 是同槽位不同职责的客户端；迭代 15 再补 reasoningMode，快慢分离不能共用客户端
        String id = slot.id().toLowerCase(Locale.ROOT);
        String key = id + "|" + withTools + "|" + reasoningMode + "|" + slot.fingerprint();
        ChatClient existing = clients.get(key);
        if (existing != null) {
            return existing;
        }
        // 迭代 10 I10-9：同槽位同职责配置变更后清理旧指纹缓存，避免长跑缓存无限增长
        String prefix = id + "|" + withTools + "|" + reasoningMode + "|";
        clients.keySet().removeIf(k -> k.startsWith(prefix) && !k.equals(key));
        return clients.computeIfAbsent(key, k -> build(slot, withTools, reasoningMode));
    }

    private ChatClient build(ModelSlot slot, boolean withTools, ReasoningMode reasoningMode) {
        ChatClient.Builder builder = ChatClient.builder(buildModel(slot));
        // 迭代 15 I15-4：深思档位进默认 options——聊天默认 DISABLED、规划/评测 HIGH、执行 LOW
        builder.defaultOptions(ReasoningOptionsApplier.builder(slot, reasoningMode));
        // 迭代 15 I15-13：用量观测统一挂到所有动态客户端
        if (usageAdvisor != null) {
            builder.defaultAdvisors(usageAdvisor);
        }
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

    /** 迭代 13：槽位 apiKey 为空时从密文库解密回填（测试连接 / 兼容旧调用方）。 */
    private ModelSlot withStoredKey(ModelSlot slot) {
        if (slot.apiKey() != null && !slot.apiKey().isBlank()) {
            return slot;
        }
        return credentials.resolve(slot.id())
                .map(resolved -> new ModelSlot(slot.id(), slot.name(), slot.protocol(), slot.baseUrl(),
                        resolved.apiKey(), slot.model(), slot.temperature(), slot.enabled()))
                .orElse(slot);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
