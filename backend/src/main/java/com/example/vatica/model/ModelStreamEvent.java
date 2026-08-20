package com.example.vatica.model;

/** 迭代 22A：模型流只暴露文本、思考摘要和最终用量，不泄漏厂商响应对象。 */
public record ModelStreamEvent(Type type, String content, ModelUsage usage) {

    public enum Type {
        TEXT,
        REASONING,
        USAGE
    }

    public static ModelStreamEvent text(String content) {
        return new ModelStreamEvent(Type.TEXT, content, null);
    }

    public static ModelStreamEvent reasoning(String content) {
        return new ModelStreamEvent(Type.REASONING, content, null);
    }

    public static ModelStreamEvent usage(ModelUsage usage) {
        return new ModelStreamEvent(Type.USAGE, null, usage);
    }
}
