package com.example.vatica.config;

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
 *
 * @param apiKey 可为空（本地 OpenAI 兼容端点如 Ollama 无需 key）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelSlot(String id, String name, String protocol, String baseUrl, String apiKey,
        String model, Double temperature, boolean enabled) {

    /** 协议常量。 */
    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_ANTHROPIC = "anthropic";

    /** 参与客户端缓存键的字段指纹（温度/协议/端点/模型任一变化即重建客户端）。 */
    public String fingerprint() {
        return protocol + "|" + nullToEmpty(baseUrl) + "|" + nullToEmpty(model)
                + "|" + temperature + "|" + nullToEmpty(apiKey);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
