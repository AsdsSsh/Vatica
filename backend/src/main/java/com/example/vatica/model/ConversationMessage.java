package com.example.vatica.model;

import java.util.Objects;

/** 迭代 22A：框架中立的会话消息，业务层不再依赖模型 SDK 的消息类型。 */
public record ConversationMessage(Role role, String text) {

    public ConversationMessage {
        role = Objects.requireNonNull(role, "role");
        text = text == null ? "" : text;
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    public static ConversationMessage system(String text) {
        return new ConversationMessage(Role.SYSTEM, text);
    }

    public static ConversationMessage user(String text) {
        return new ConversationMessage(Role.USER, text);
    }

    public static ConversationMessage assistant(String text) {
        return new ConversationMessage(Role.ASSISTANT, text);
    }
}
