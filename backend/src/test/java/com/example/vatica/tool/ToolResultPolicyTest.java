package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 迭代 15 I15-10：工具输出预算——小输出原样、超限保留头尾并打标记。 */
class ToolResultPolicyTest {

    @Test
    void smallOutputPassesThrough() {
        assertThat(ToolResultPolicy.limit("短结果")).isEqualTo("短结果");
    }

    @Test
    void oversizedOutputKeepsHeadAndTailWithMarker() {
        String head = "头".repeat(ToolResultPolicy.HEAD_CHARS);
        String middle = "中".repeat(500);
        String tail = "尾".repeat(ToolResultPolicy.TAIL_CHARS);
        String out = head + middle + tail;

        String limited = ToolResultPolicy.limit(out);

        assertThat(limited).startsWith(head).contains("工具输出已截断").endsWith(tail);
        assertThat(limited.length()).isLessThan(out.length());
        assertThat(limited).doesNotContain("中".repeat(500));
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(ToolResultPolicy.limit(null)).isEmpty();
    }

    @Test
    void skillSpecificLimitNeverExceedsDeclaredBudget() {
        String limited = ToolResultPolicy.limit("x".repeat(7_000), 6_000);

        assertThat(limited).hasSize(6_000)
                .contains("Skill 工具输出已按 6000 字符上限截断", "原始 7000 字符");
    }
}
