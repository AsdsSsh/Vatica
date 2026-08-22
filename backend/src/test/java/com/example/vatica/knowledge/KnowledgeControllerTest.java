package com.example.vatica.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeControllerTest {

    @Test
    void exposesNonSensitiveVectorReadinessContract() throws Exception {
        KnowledgeBaseService service = mock(KnowledgeBaseService.class);
        JdbcKnowledgeVectorIndex index = mock(JdbcKnowledgeVectorIndex.class);
        when(index.readiness()).thenReturn(new JdbcKnowledgeVectorIndex.Readiness(true, true, true, false,
                "0.8.5", "pgvector-v1", "openai", "text-embedding-3-small", 1536,
                "fingerprint", "HNSW 不可用，将使用精确余弦检索。"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(service, index)).build();

        mvc.perform(get("/api/knowledge/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.postgres").value(true))
                .andExpect(jsonPath("$.extensionInstalled").value(true))
                .andExpect(jsonPath("$.indexReady").value(false))
                .andExpect(jsonPath("$.vectorDimensions").value(1536))
                .andExpect(jsonPath("$.configFingerprint").value("fingerprint"));
    }
}
