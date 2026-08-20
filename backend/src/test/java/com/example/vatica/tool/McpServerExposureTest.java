package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * MCP Server 工具暴露验证（迭代 22C）：官方 MCP Java SDK 的
 * SyncToolSpecification 列表即 Streamable HTTP /mcp 的 tools/list 事实源。
 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class McpServerExposureTest {

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("mcpServerTools")
    List<SyncToolSpecification> mcpServerTools;

    @Autowired
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet;

    @Test
    void exposesAllEighteenLocalTools() {
        List<String> names = mcpServerTools.stream().map(t -> t.tool().name()).sorted().toList();

        assertThat(names).containsExactlyInAnyOrder(
                "read_file", "write_file", "list_files", "calculator", "text_stats",
                "create_word_report", "create_excel_stats",
                "calendar_query", "calendar_create", "calendar_import",
                "todo_add", "todo_list", "todo_complete", "todo_remind",
                "mail_query", "mail_send", "list_workspace_roots", "search_knowledge_base");
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

    @Test
    void registersOfficialStreamableHttpServletAtMcpEndpoint() {
        assertThat(mcpServlet.getUrlMappings()).contains("/mcp");
        assertThat(mcpServlet.getServlet()).isInstanceOf(HttpServletStreamableServerTransportProvider.class);
    }
}
