package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * MCP Server 工具暴露验证（迭代 4 I4-2）：全上下文 + 禁用 MCP 客户端，
 * 断言本地 16 个工具全部注册进 MCP Server 的 SyncToolSpecification 列表
 * （即经 Streamable HTTP /mcp 暴露给任何 MCP 客户端）。
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class McpServerExposureTest {

    @Autowired
    @Qualifier("syncTools")
    List<SyncToolSpecification> mcpServerTools;

    @Test
    void exposesAllSixteenLocalTools() {
        List<String> names = mcpServerTools.stream().map(t -> t.tool().name()).sorted().toList();

        assertThat(names).containsExactlyInAnyOrder(
                "read_file", "write_file", "list_files", "calculator", "text_stats",
                "create_word_report", "create_excel_stats",
                "calendar_query", "calendar_create", "calendar_import",
                "todo_add", "todo_list", "todo_complete", "todo_remind",
                "mail_query", "mail_send");
    }

    @Test
    void toolSchemasCarryDescriptions() {
        SyncToolSpecification calendarQuery = mcpServerTools.stream()
                .filter(t -> t.tool().name().equals("calendar_query"))
                .findFirst()
                .orElseThrow();

        assertThat(calendarQuery.tool().description()).contains("日程");
        // 入参 schema 存在（MCP 客户端据此生成调用参数）
        assertThat(calendarQuery.tool().inputSchema()).isNotNull();
    }
}
