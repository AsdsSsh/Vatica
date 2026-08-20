package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 23D：聊天模型清单返回可调用性，不能仅映射 enabled。 */
class ChatControllerModelsTest {

    @Test
    void modelListMarksEnabledRemoteSlotWithoutKeyAsUnconfigured() {
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.slots()).thenReturn(List.of(
                new ModelSlot("remote", "Remote", "openai", "https://api.example.test", "", "chat", 0.2, true),
                new ModelSlot("local", "Local", "openai", "http://localhost:11434/v1", "", "chat", 0.2, true)));
        ChatController controller = new ChatController(registry, null, null, null, null, null,
                new ObjectMapper(), null, null);

        assertThat(controller.models()).extracting(ModelInfoDto::id, ModelInfoDto::configured)
                .containsExactly(tuple("remote", false), tuple("local", true));
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
