package com.example.vatica.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void exposesRetryAndRebuildLifecycleEndpoints() throws Exception {
        KnowledgeBaseService service = mock(KnowledgeBaseService.class);
        JdbcKnowledgeVectorIndex index = mock(JdbcKnowledgeVectorIndex.class);
        KnowledgeBaseService.DocumentView view = new KnowledgeBaseService.DocumentView(7L, "guide.md",
                "docs/guide.md", KnowledgeVisibility.PRIVATE, "hash", 2, KnowledgeDocumentStatus.READY,
                4, 4, 4, 100, 2, null, "2026-08-22T00:00:00Z");
        when(service.retryDocument(7L)).thenReturn(view);
        when(service.rebuildDocument(7L)).thenReturn(view);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(service, index)).build();

        mvc.perform(post("/api/knowledge/documents/7/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.indexAttempt").value(2));
        mvc.perform(post("/api/knowledge/documents/7/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChunks").value(4));
    }

    @Test
    void exposesCitationLocationVersionAndPermissionContext() throws Exception {
        KnowledgeBaseService service = mock(KnowledgeBaseService.class);
        JdbcKnowledgeVectorIndex index = mock(JdbcKnowledgeVectorIndex.class);
        KnowledgeBaseService.Citation citation = new KnowledgeBaseService.Citation("C1", 7L, "制度.md",
                "docs/制度.md", 9L, "审批", 12, 48, 0.91, "报销超过一万元需要二级审批。",
                "章节：“审批” · 字符 13-48", "报销超过一万元需要二级审批。", 3, "pgvector-v1",
                "ORGANIZATION_SHARED");
        when(service.search("报销怎么审批", 5)).thenReturn(new KnowledgeBaseService.SearchResult("报销怎么审批",
                "pgvector-v1", "CURRENT_USER_PRIVATE_AND_ORG_SHARED", java.util.List.of(citation)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(service, index)).build();

        mvc.perform(post("/api/knowledge/search").contentType("application/json")
                        .content("{\"query\":\"报销怎么审批\",\"topK\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexVersion").value("pgvector-v1"))
                .andExpect(jsonPath("$.citations[0].sourceLocation").value("章节：“审批” · 字符 13-48"))
                .andExpect(jsonPath("$.citations[0].documentVersion").value(3))
                .andExpect(jsonPath("$.citations[0].accessScope").value("ORGANIZATION_SHARED"));
    }
}
