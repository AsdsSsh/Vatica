package com.example.vatica.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 迭代 17A：角色注册、非法角色回退与机械工具门禁。 */
class AgentRegistryTest {

    private final AgentRegistry registry = new AgentRegistry();

    @Test
    void registersFiveStableRolesAndFallsBackToGeneral() {
        assertThat(registry.definitions()).extracting(AgentRegistry.AgentDefinition::id)
                .containsExactlyInAnyOrder("document", "pim", "workspace", "research", "general");
        assertThat(registry.normalizeId(null)).isEqualTo("general");
        assertThat(registry.normalizeId("UNKNOWN")).isEqualTo("general");
        assertThat(registry.normalizeId(" PIM ")).isEqualTo("pim");
    }

    @Test
    void documentRoleCannotReceiveWorkspaceOrResearchTools() {
        ToolCallback word = callback("create_word_report");
        ToolCallback read = callback("read_file");
        ToolCallback calculator = callback("calculator");

        assertThat(registry.allowedCallbacks("document", new ToolCallback[] { word, read, calculator }))
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("create_word_report");
    }

    @Test
    void pimRoleCannotReceiveWorkspaceTools() {
        ToolCallback calendar = callback("calendar_query");
        ToolCallback mail = callback("mail_send");
        ToolCallback write = callback("write_file");

        assertThat(registry.allowedCallbacks("pim", new ToolCallback[] { calendar, mail, write }))
                .containsExactly(calendar, mail);
    }

    @Test
    void generalRoleKeepsAllRegisteredTools() {
        ToolCallback read = callback("read_file");
        ToolCallback mail = callback("mail_send");

        assertThat(registry.allowedCallbacks("general", new ToolCallback[] { read, mail }))
                .containsExactly(read, mail);
    }

    @Test
    void researchRoleAcceptsAmapMcpToolsButRejectsPimTools() {
        ToolCallback weather = callback("maps_weather");
        ToolCallback mail = callback("mail_send");

        assertThat(registry.allowedCallbacks("research", new ToolCallback[] { weather, mail }))
                .containsExactly(weather);
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{}").build());
        return callback;
    }
}
