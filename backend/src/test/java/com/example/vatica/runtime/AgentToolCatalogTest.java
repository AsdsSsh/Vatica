package com.example.vatica.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.example.vatica.knowledge.JdbcKnowledgeVectorIndex;
import com.example.vatica.knowledge.KnowledgeProperties;
import com.example.vatica.tool.AgentToolProvider;

import io.agentscope.core.tool.AgentTool;

class AgentToolCatalogTest {

    @Test
    void removesKnowledgeToolBeforePlannerSeesUnavailableIndex() {
        AgentTool general = tool("calculator");
        AgentTool knowledge = tool("search_knowledge_base");
        AgentToolProvider local = () -> new AgentTool[] { general, knowledge };
        JdbcKnowledgeVectorIndex index = mock(JdbcKnowledgeVectorIndex.class);
        when(index.readiness()).thenReturn(new JdbcKnowledgeVectorIndex.Readiness(false, true));
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentToolProvider> remote = mock(ObjectProvider.class);

        AgentToolCatalog catalog = new AgentToolCatalog(local, remote,
                new KnowledgeProperties(true, "local-hash", 3, 1024, 100, 20, 1000, null), index);

        assertThat(Arrays.stream(catalog.tools()).map(AgentTool::getName))
                .containsExactly("calculator");
    }

    private static AgentTool tool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        return tool;
    }
}
