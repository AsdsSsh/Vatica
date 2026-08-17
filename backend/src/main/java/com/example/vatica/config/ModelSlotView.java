package com.example.vatica.config;

import java.util.List;

/**
 * 模型槽位对外视图（迭代 13 I13-3）：永远不回传完整 apiKey，只给 set/hint。
 * 保存请求仍用 {@link ModelSlot}（apiKey 字段 null=keep / 空串=clear / 非空=set）。
 * 迭代 15 I15-5：增加能力标签列表。
 */
public record ModelSlotView(String id, String name, String protocol, String baseUrl, String model,
        Double temperature, boolean enabled, List<String> capabilities, String promptCacheKey,
        boolean apiKeySet, String apiKeyHint) {
}
