package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 迭代 15 I15-4：深思选项按协议映射——DeepSeek extraBody、OpenAI 系 reasoningEffort、Anthropic thinking。 */
class ReasoningOptionsApplierTest {

    private static ModelSlot slot(String baseUrl, String protocol) {
        return new ModelSlot("m", "m", protocol, baseUrl, "", "model-x", 0.7, true);
    }

    @Test
    void deepSeekUsesThinkingExtraBodyAndDisabledIsExplicit() {
        OpenAiChatOptions disabled = ReasoningOptionsApplier
                .openAi(slot("https://api.deepseek.com", ModelSlot.PROTOCOL_OPENAI), ReasoningMode.DISABLED).build();
        OpenAiChatOptions high = ReasoningOptionsApplier
                .openAi(slot("https://api.deepseek.com", ModelSlot.PROTOCOL_OPENAI), ReasoningMode.HIGH).build();

        assertThat(disabled.getExtraBody()).isNotNull();
        assertThat(String.valueOf(disabled.getExtraBody().get("thinking"))).contains("disabled");
        assertThat(String.valueOf(high.getExtraBody().get("thinking"))).contains("enabled");
    }

    @Test
    void openAiCompatibleUsesReasoningEffortOnlyWhenEnabled() {
        OpenAiChatOptions disabled = ReasoningOptionsApplier
                .openAi(slot("https://api.openai.com", ModelSlot.PROTOCOL_OPENAI), ReasoningMode.DISABLED).build();
        OpenAiChatOptions low = ReasoningOptionsApplier
                .openAi(slot("https://api.openai.com", ModelSlot.PROTOCOL_OPENAI), ReasoningMode.LOW).build();

        assertThat(disabled.getReasoningEffort()).isNull();
        assertThat(low.getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void anthropicUsesThinkingConfig() {
        AnthropicChatOptions disabled = ReasoningOptionsApplier
                .anthropic(slot("https://api.anthropic.com", ModelSlot.PROTOCOL_ANTHROPIC), ReasoningMode.DISABLED).build();
        AnthropicChatOptions high = ReasoningOptionsApplier
                .anthropic(slot("https://api.anthropic.com", ModelSlot.PROTOCOL_ANTHROPIC), ReasoningMode.HIGH).build();

        assertThat(disabled).isNotNull();
        assertThat(high).isNotNull();
    }
}
