package com.example.vatica.config;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.formatter.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

/**
 * 迭代 22D：Vatica 的动态模型注册表。
 * 模型实例、消息和工具调用均由 AgentScope 原生 API 承载；本类只维护槽位、凭据与角色选型。
 */
@Component
public class ModelRegistry {

    private final ModelConfigService config;
    private final ModelCredentialStore credentials;
    private final UserModelService userModels;
    private final ConcurrentHashMap<String, Model> models = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> roleOffsets = new ConcurrentHashMap<>();

    public ModelRegistry(ModelConfigService config, ModelCredentialStore credentials, UserModelService userModels) {
        this.config = config;
        this.credentials = credentials;
        this.userModels = userModels;
    }

    public List<ModelSlot> slots() {
        return config.slots();
    }

    public ModelSlot defaultSlot() {
        return slots().stream().filter(ModelSlot::enabled).findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：没有启用的模型，请先在设置中配置。"));
    }

    public ModelSlot slotFor(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return defaultSlot();
        }
        return slots().stream().filter(slot -> slot.id().equalsIgnoreCase(modelId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作失败：未知模型（" + modelId + "）。"));
    }

    /** 用户已加密保存的槽位转换为与平台槽位一致的不可变模型快照。 */
    public ModelSlot userSlot(Long ownerId, String slotId) {
        UserModelSlot slot = userModels.resolveSlot(ownerId, slotId);
        if (UserModelSlot.MODE_EPHEMERAL.equals(slot.getCredentialMode())) {
            throw new IllegalArgumentException("操作失败：该模型为仅本机模式，请随请求提供 credential 后重试。");
        }
        return new ModelSlot("user:" + slotId, slot.getName(), slot.getProtocol(), slot.getBaseUrl(),
                userModels.resolveApiKey(ownerId, slotId), slot.getModel(), slot.getTemperature(), true);
    }

    /** AgentScope 原生模型缓存；配置指纹变化会丢弃相同槽位的旧实例。 */
    public Model agentScopeModel(ModelSlot slot) {
        ModelSlot resolved = withStoredKey(slot);
        if (resolved == null || !resolved.enabled()) {
            throw new IllegalArgumentException("操作失败：模型槽位不可用。");
        }
        String id = resolved.id().toLowerCase(Locale.ROOT);
        String key = id + "|" + resolved.fingerprint();
        models.keySet().removeIf(existing -> existing.startsWith(id + "|") && !existing.equals(key));
        return models.computeIfAbsent(key, ignored -> build(resolved));
    }

    /** 角色能力优先，旧配置未声明 capability 时兼容回退默认槽位。 */
    public ModelSlot activeSlotFor(String capability) {
        List<ModelSlot> candidates = slots().stream().filter(ModelSlot::enabled)
                .filter(slot -> slot.capabilities().contains(capability)).toList();
        if (candidates.isEmpty()) {
            return defaultSlot();
        }
        int offset = Math.floorMod(roleOffsets.computeIfAbsent(capability, ignored -> new AtomicInteger()).get(),
                candidates.size());
        return candidates.get(offset);
    }

    /** 控制器连通性检查：直接运行一个无工具 AgentScope 回合。 */
    public String testConnection(ModelSlot slot) {
        ReActAgent agent = ReActAgent.builder().name("vatica-connection-check")
                .sysPrompt("你是连接检测助手。").model(agentScopeModel(slot)).toolkit(new Toolkit()).maxIters(1)
                .generateOptions(GenerateOptions.builder().temperature(0d).build()).build();
        try {
            var reply = agent.call(List.of(new UserMessage("请只回复两个字：正常")),
                    RuntimeContext.builder().userId("connection-check").sessionId("connection-check").build()).block();
            return reply == null || reply.getTextContent() == null ? "" : reply.getTextContent();
        } finally {
            agent.close();
        }
    }

    private static Model build(ModelSlot slot) {
        GenerateOptions options = GenerateOptions.builder().temperature(slot.temperature()).build();
        if (ModelSlot.PROTOCOL_OPENAI.equals(slot.protocol())) {
            boolean deepseek = slot.baseUrl() != null && slot.baseUrl().toLowerCase(Locale.ROOT).contains("deepseek");
            return io.agentscope.extensions.model.openai.OpenAIChatModel.builder()
                    .apiKey(slot.apiKey() == null ? "" : slot.apiKey()).baseUrl(slot.baseUrl()).modelName(slot.model())
                    .stream(false).generateOptions(options)
                    .formatter(deepseek ? new DeepSeekFormatter() : new OpenAIChatFormatter())
                    .contextWindowSize(16_000).build();
        }
        if (ModelSlot.PROTOCOL_ANTHROPIC.equals(slot.protocol())) {
            return io.agentscope.extensions.model.anthropic.AnthropicChatModel.builder()
                    .apiKey(slot.apiKey() == null ? "" : slot.apiKey()).baseUrl(slot.baseUrl()).modelName(slot.model())
                    .stream(false).defaultOptions(options).contextWindowSize(16_000).build();
        }
        throw new IllegalArgumentException("操作失败：不支持的协议（" + slot.protocol() + "）。");
    }

    private ModelSlot withStoredKey(ModelSlot slot) {
        if (slot == null || (slot.apiKey() != null && !slot.apiKey().isBlank())) {
            return slot;
        }
        return credentials.resolve(slot.id()).map(key -> new ModelSlot(slot.id(), slot.name(), slot.protocol(),
                slot.baseUrl(), key.apiKey(), slot.model(), slot.temperature(), slot.enabled(),
                slot.capabilities(), slot.promptCacheKey())).orElse(slot);
    }
}
