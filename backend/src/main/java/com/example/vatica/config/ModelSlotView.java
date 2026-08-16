package com.example.vatica.config;

/**
 * 模型槽位对外视图（迭代 13 I13-3）：永远不回传完整 apiKey，只给 set/hint。
 * 保存请求仍用 {@link ModelSlot}（apiKey 字段 null=keep / 空串=clear / 非空=set）。
 */
public record ModelSlotView(String id, String name, String protocol, String baseUrl, String model,
        Double temperature, boolean enabled, boolean apiKeySet, String apiKeyHint) {
}
