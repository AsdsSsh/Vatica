package com.example.vatica.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 15 I15-1：trace 脱敏——敏感字段值一律 ***，长文本只保留摘要。 */
class TraceSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void masksSensitiveJsonFieldsRecursively() {
        String raw = """
                {"path":"/a.txt","credential":{"apiKey":"sk-123","password":"p","nested":{"token":"t1"}},
                 "mail":{"authorization":"Bearer x"},"items":[{"secret":"s","name":"ok"}]}""";

        String sanitized = TraceSanitizer.sanitize(mapper, raw);

        assertThat(sanitized).doesNotContain("sk-123", "t1", "Bearer x");
        assertThat(sanitized).contains("\"apiKey\":\"***\"", "\"password\":\"***\"", "\"token\":\"***\"",
                "\"authorization\":\"***\"", "\"secret\":\"***\"");
        assertThat(sanitized).contains("/a.txt", "ok");
    }

    @Test
    void masksRawKeyValueSecretsWhenInputIsNotJson() {
        String sanitized = TraceSanitizer.sanitize(mapper, "path=/a token=abc123 api_key=sk-9");

        assertThat(sanitized).doesNotContain("abc123", "sk-9");
        assertThat(sanitized).contains("token: ***", "api_key: ***");
    }

    @Test
    void inputSummaryTruncatesAfterSanitizing() {
        String longJson = "{\"apiKey\":\"sk-123\",\"text\":\"" + "x".repeat(500) + "\"}";

        String summary = TraceSanitizer.inputSummary(mapper, longJson);

        assertThat(summary).doesNotContain("sk-123");
        assertThat(summary).endsWith("…（输入已截断）");
        assertThat(summary.length()).isLessThanOrEqualTo(TraceSanitizer.MAX_INPUT_CHARS + "…（输入已截断）".length());
    }

    @Test
    void outputSummaryKeepsHeadAndTailWithMarkerAndReportsLength() {
        String out = "开头内容" + "x".repeat(400) + "结尾内容";
        StringBuilder length = new StringBuilder();

        String summary = TraceSanitizer.outputSummary(out, length);

        assertThat(summary).startsWith("开头内容").contains("…（输出已截断，共 " + out.length() + " 字符）…")
                .endsWith("结尾内容");
        assertThat(length.toString()).isEqualTo(String.valueOf(out.length()));
    }

    @Test
    void shortOutputPassesThroughVerbatim() {
        StringBuilder length = new StringBuilder();

        String summary = TraceSanitizer.outputSummary("短结果", length);

        assertThat(summary).isEqualTo("短结果");
        assertThat(length.toString()).isEqualTo("3");
    }
}
