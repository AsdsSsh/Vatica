package com.example.vatica.config;

/**
 * 请求级临时凭据（迭代 13 I13-5）：客户端每次请求随 body 传入的自配模型连接信息。
 * 只允许存活在请求上下文：不写库、不写文件、不写日志、不进共享缓存。
 */
public record EphemeralCredential(String protocol, String baseUrl, String model, Double temperature, String apiKey) {

    public EphemeralCredential {
        if (protocol == null || protocol.isBlank()) {
            protocol = ModelSlot.PROTOCOL_OPENAI;
        }
        protocol = protocol.toLowerCase(java.util.Locale.ROOT);
        if (!protocol.equals(ModelSlot.PROTOCOL_OPENAI) && !protocol.equals(ModelSlot.PROTOCOL_ANTHROPIC)) {
            throw new IllegalArgumentException("操作失败：不支持的临时凭据协议（" + protocol + "）。");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("操作失败：临时凭据必须填写 Base URL。");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("操作失败：临时凭据必须填写模型 ID。");
        }
        if (temperature == null) {
            temperature = 0.7;
        }
        if (apiKey == null) {
            apiKey = "";
        }
    }

    public ModelSlot toSlot() {
        return new ModelSlot("ephemeral", "临时模型 " + model, protocol, baseUrl.trim(), apiKey.trim(),
                model.trim(), temperature, true);
    }
}
