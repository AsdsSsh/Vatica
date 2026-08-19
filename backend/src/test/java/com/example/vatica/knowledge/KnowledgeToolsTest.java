package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class KnowledgeToolsTest {

    @Test
    void serializesCitationIdsAndSourceLocationsForAgents() {
        KnowledgeBaseService service = mock(KnowledgeBaseService.class);
        var citation = new KnowledgeBaseService.Citation("C1", 2L, "制度.md", "docs/制度.md",
                3L, "审批", 12, 48, 0.9234, "报销超过一万元需要二级审批。");
        when(service.search("报销怎么审批", 5))
                .thenReturn(new KnowledgeBaseService.SearchResult("报销怎么审批", List.of(citation)));

        String json = new KnowledgeTools(service, new ObjectMapper()).search("报销怎么审批", null);

        assertThat(json).contains("\"citationId\":\"C1\"")
                .contains("\"sourcePath\":\"docs/制度.md\"")
                .contains("\"startOffset\":12")
                .contains("报销超过一万元需要二级审批");
    }
}
