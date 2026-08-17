package com.example.vatica.config;

import java.util.Map;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 迭代 15 I15-4：按协议映射深思开关。
 * OpenAI 系用 reasoningEffort（low/medium/high）；DeepSeek 用 extraBody 的
 * {"thinking":{"type":"enabled|disabled"}}；Anthropic 用 thinkingDisabled/thinkingEnabled。
 * DISABLED 时 OpenAI 系不发 reasoningEffort（不显式开启思考），DeepSeek 必须显式 disabled
 * （v4 默认思考开启），不支持的供应商自然忽略。
 */
public final class ReasoningOptionsApplier {

    private ReasoningOptionsApplier() {
    }

    public static ChatOptions.Builder<?> builder(ModelSlot slot, ReasoningMode mode) {
        if (ModelSlot.PROTOCOL_ANTHROPIC.equals(slot.protocol())) {
            return anthropic(slot, mode);
        }
        return openAi(slot, mode);
    }

    public static OpenAiChatOptions.Builder openAi(ModelSlot slot, ReasoningMode mode) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(slot.model())
                .temperature(slot.temperature());
        if (slot.baseUrl() != null && slot.baseUrl().toLowerCase(java.util.Locale.ROOT).contains("deepseek")) {
            builder.extraBody(Map.of("thinking", Map.of("type",
                    mode == ReasoningMode.DISABLED ? "disabled" : "enabled")));
        } else if (mode != ReasoningMode.DISABLED) {
            builder.reasoningEffort(mode.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (slot.promptCacheKey() != null && !slot.promptCacheKey().isBlank()) {
            builder.promptCacheKey(slot.promptCacheKey());
        }
        return builder;
    }

    public static AnthropicChatOptions.Builder anthropic(ModelSlot slot, ReasoningMode mode) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                .model(com.anthropic.models.messages.Model.Companion.of(slot.model()))
                .temperature(slot.temperature());
        if (mode == ReasoningMode.DISABLED) {
            builder.thinkingDisabled();
        } else {
            long budgetTokens = switch (mode) {
                case LOW -> 4096;
                case MEDIUM -> 8192;
                case HIGH -> 16384;
                case DISABLED -> 0;
            };
            builder.thinkingEnabled(budgetTokens);
        }
        return builder;
    }
}
