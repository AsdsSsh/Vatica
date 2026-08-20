package com.example.vatica.model;

/** 迭代 22A：非流式模型结果。 */
public record ModelResponse(String content, String reasoning, ModelUsage usage) {

    public ModelResponse {
        content = content == null ? "" : content;
        reasoning = reasoning == null ? "" : reasoning;
        usage = usage == null ? ModelUsage.empty() : usage;
    }
}
