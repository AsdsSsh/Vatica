package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Windows 注册表回填解析测试（迭代 10 热修复）：不调用真实 reg，锁死输出解析规则。 */
class WindowsRegistryEnvBackfillTest {

    @Test
    void parseValueExtractsAfterRegSz() {
        String output = """
                HKEY_CURRENT_USER\\Environment
                    MYSQL_PASSWORD    REG_SZ    demo-pass
                """;

        assertThat(WindowsRegistryEnvBackfill.parseValue(output, "MYSQL_PASSWORD"))
                .isEqualTo("demo-pass");
    }

    @Test
    void parseValueKeepsInnerSpacesButTrimsEdges() {
        String output = """
                HKEY_CURRENT_USER\\Environment
                    MAIL_PASSWORD    REG_SZ      p a s s  word
                """;

        assertThat(WindowsRegistryEnvBackfill.parseValue(output, "MAIL_PASSWORD"))
                .isEqualTo("p a s s  word");
    }

    @Test
    void parseValueReturnsNullWhenMissingOrEmpty() {
        assertThat(WindowsRegistryEnvBackfill.parseValue("HKEY_CURRENT_USER\\Environment", "MYSQL_PASSWORD"))
                .isNull();
        assertThat(WindowsRegistryEnvBackfill.parseValue(
                "    MYSQL_PASSWORD    REG_SZ       ", "MYSQL_PASSWORD")).isNull();
    }
}
