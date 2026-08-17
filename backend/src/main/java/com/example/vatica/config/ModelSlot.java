package com.example.vatica.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 模型槽位（迭代 8.5 模型配置中心）：界面可配置的一个模型连接。
 *
 * <p>协议两种，覆盖主流模型生态：
 * <ul>
 *   <li>{@code openai}：OpenAI 兼容协议（DeepSeek/通义/Moonshot/GLM/Ollama 本地等，改 base-url 即切换）</li>
 *   <li>{@code anthropic}：Anthropic Messages 协议（Claude 及兼容端点）</li>
 * </ul>
 *
 * <p><b>迭代 13.5</b>：前端保存时会把列表视图的 {@code apiKeySet/apiKeyHint} 一起回传，
 * 这里忽略未知字段（API key 只认 {@code apiKey}：null=keep / 空串=clear / 非空=set）。
 * <b>迭代 15 I15-5</b>：capabilities 声明槽位可承担的角色（chat-fast/chat-reason/planner/judge/summarizer）。
 * <b>迭代 15 I15-14</b>：promptCacheKey 为 OpenAI 兼容端点声明 prompt 缓存前缀（空=不启用）。
 *
 * @param apiKey 可为空（本地 OpenAI 兼容端点如 Ollama 无需 key）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelSlot(String id, String name, String protocol, String baseUrl, String apiKey,
        String model, Double temperature, boolean enabled, List<String> capabilities,
        String promptCacheKey) {

    /** 协议常量。 */
    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_ANTHROPIC = "anthropic";

    /** 迭代 15 I15-5：槽位能力标签。 */
    public static final String CAP_CHAT_FAST = "chat-fast";
    public static final String CAP_CHAT_REASON = "chat-reason";
    public static final String CAP_PLANNER = "planner";
    public static final String CAP_JUDGE = "judge";
    public static final String CAP_SUMMARIZER = "summarizer";

    public ModelSlot {
        capabilities = capabilities == null ? List.of() : capabilities.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        promptCacheKey = promptCacheKey == null || promptCacheKey.isBlank() ? "" : promptCacheKey.trim();
    }

    /** 旧契约兼容：未声明能力时默认空列表（角色解析回退到默认槽位）。 */
    public ModelSlot(String id, String name, String protocol, String baseUrl, String apiKey,
            String model, Double temperature, boolean enabled) {
        this(id, name, protocol, baseUrl, apiKey, model, temperature, enabled, List.of(), "");
    }

    /** 迭代 15 I15-5 契约兼容。 */
    public ModelSlot(String id, String name, String protocol, String baseUrl, String apiKey,
            String model, Double temperature, boolean enabled, List<String> capabilities) {
        this(id, name, protocol, baseUrl, apiKey, model, temperature, enabled, capabilities, "");
    }

    /** 参与客户端缓存键的字段指纹（温度/协议/端点/模型/能力/缓存前缀任一变化即重建客户端）。 */
    public String fingerprint() {
        return protocol + "|" + nullToEmpty(baseUrl) + "|" + nullToEmpty(model)
                + "|" + temperature + "|" + nullToEmpty(apiKey) + "|" + capabilities
                + "|" + nullToEmpty(promptCacheKey);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
