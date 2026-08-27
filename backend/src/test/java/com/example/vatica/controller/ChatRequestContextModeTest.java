package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.vatica.context.ContextMode;

/** 迭代 31D：旧构造器和缺省 JSON 语义仍保持普通上下文模式。 */
class ChatRequestContextModeTest {

    @Test
    void legacyConstructorDefaultsToNormalMode() {
        ChatRequest request = new ChatRequest("hello", "s1", null, null, null, null, false);

        assertThat(request.contextMode()).isEqualTo(ContextMode.NORMAL);
    }

    @Test
    void nullModeIsNormalizedToNormal() {
        ChatRequest request = new ChatRequest("hello", "s1", null, null, null, null, false, null);

        assertThat(request.contextMode()).isEqualTo(ContextMode.NORMAL);
    }
}
