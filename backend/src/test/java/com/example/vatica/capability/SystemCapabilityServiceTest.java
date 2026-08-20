package com.example.vatica.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.McpProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.knowledge.JdbcKnowledgeVectorIndex;
import com.example.vatica.knowledge.KnowledgeProperties;
import com.example.vatica.mail.MailCredentialMode;
import com.example.vatica.mail.UserMailService;
import com.example.vatica.tool.AgentToolProvider;
import com.example.vatica.workspace.WorkspaceStore;

class SystemCapabilityServiceTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void reportsActionableStatusWithoutLeakingInfrastructureDetails() {
        ModelRegistry models = mock(ModelRegistry.class);
        when(models.slots()).thenReturn(List.of(new ModelSlot("local", "Local", "openai",
                "http://localhost:11434/v1", "", "qwen", 0.2, true)));
        JdbcKnowledgeVectorIndex index = mock(JdbcKnowledgeVectorIndex.class);
        when(index.readiness()).thenReturn(new JdbcKnowledgeVectorIndex.Readiness(false, true));
        UserMailService mail = mock(UserMailService.class);
        when(mail.get()).thenReturn(new UserMailService.View(MailCredentialMode.EPHEMERAL,
                "", 993, "", 465, "", false, null));
        WorkspaceStore workspace = mock(WorkspaceStore.class);
        RequestIdentity identity = new RequestIdentity(7L, 9L, "USER", "alice");
        when(workspace.root(identity)).thenReturn(workspaceRoot);

        McpProperties properties = new McpProperties(new McpProperties.Client(true, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMinutes(1), Map.of("map", new McpProperties.Connection(true,
                        "https://mcp.example.test", true, "", Map.of()))), null);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentToolProvider> remoteTools = mock(ObjectProvider.class);

        SystemCapabilityService service = new SystemCapabilityService(models,
                new DriverManagerDataSource("jdbc:h2:mem:capability-status;DB_CLOSE_DELAY=-1", "sa", ""),
                new KnowledgeProperties(true, "local-hash", 3, 1024, 100, 20, 1000, null), index, properties,
                remoteTools, mail, workspace);

        Map<String, SystemCapabilityService.CapabilityView> statuses = service.snapshot(identity).capabilities().stream()
                .collect(java.util.stream.Collectors.toMap(SystemCapabilityService.CapabilityView::id, value -> value));

        assertThat(statuses.get("model").status()).isEqualTo(SystemCapabilityService.Status.READY);
        assertThat(statuses.get("database").status()).isEqualTo(SystemCapabilityService.Status.READY);
        assertThat(statuses.get("knowledge").status()).isEqualTo(SystemCapabilityService.Status.UNAVAILABLE);
        assertThat(statuses.get("knowledge").message()).doesNotContain("example.test");
        assertThat(statuses.get("mcp").status()).isEqualTo(SystemCapabilityService.Status.ACTION_REQUIRED);
        assertThat(statuses.get("mail").status()).isEqualTo(SystemCapabilityService.Status.ACTION_REQUIRED);
        assertThat(statuses.get("workspace").status()).isEqualTo(SystemCapabilityService.Status.READY);
    }
}
