package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.example.vatica.auth.RequestIdentity;

class JdbcKnowledgeVectorIndexTest {

    @Test
    void fallbackSearchEnforcesOrganizationAndUserVisibility() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-index;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        KnowledgeProperties properties = new KnowledgeProperties(
                true, "local-hash", 3, 1024 * 1024, 100, 20, 2000, null);
        JdbcKnowledgeVectorIndex index = new JdbcKnowledgeVectorIndex(new JdbcTemplate(dataSource), dataSource, properties);

        index.upsert(document(1L, 9L, 7L, KnowledgeVisibility.PRIVATE), chunk(11L), new float[] { 1, 0, 0 });
        index.upsert(document(2L, 9L, 8L, KnowledgeVisibility.ORG_SHARED), chunk(12L), new float[] { 0.9f, 0.1f, 0 });
        index.upsert(document(3L, 9L, 8L, KnowledgeVisibility.PRIVATE), chunk(13L), new float[] { 1, 0, 0 });
        index.upsert(document(4L, 10L, 7L, KnowledgeVisibility.ORG_SHARED), chunk(14L), new float[] { 1, 0, 0 });

        assertThat(index.search(new RequestIdentity(7L, 9L, "USER", "alice"), new float[] { 1, 0, 0 }, 8))
                .extracting(KnowledgeVectorIndex.Match::chunkId)
                .containsExactly(11L, 12L);
    }

    @Test
    void h2ReadinessIsExplicitlyMarkedAsLocalFallback() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        KnowledgeProperties properties = new KnowledgeProperties(
                true, "local-hash", 3, 1024 * 1024, 100, 20, 2000, null);

        JdbcKnowledgeVectorIndex index = new JdbcKnowledgeVectorIndex(new JdbcTemplate(dataSource), dataSource, properties);

        assertThat(index.readiness().ready()).isTrue();
        assertThat(index.readiness().postgres()).isFalse();
        assertThat(index.readiness().extensionInstalled()).isFalse();
        assertThat(index.readiness().indexReady()).isFalse();
        assertThat(index.readiness().schemaVersion()).isEqualTo("h2-fallback-v1");
        assertThat(index.readiness().message()).contains("H2");
    }

    private static KnowledgeDocumentRecord document(long id, long orgId, long userId,
            KnowledgeVisibility visibility) {
        KnowledgeDocumentRecord document = mock(KnowledgeDocumentRecord.class);
        when(document.getId()).thenReturn(id);
        when(document.getOrgId()).thenReturn(orgId);
        when(document.getUserId()).thenReturn(userId);
        when(document.getVisibility()).thenReturn(visibility);
        return document;
    }

    private static KnowledgeChunkRecord chunk(long id) {
        KnowledgeChunkRecord chunk = mock(KnowledgeChunkRecord.class);
        when(chunk.getId()).thenReturn(id);
        return chunk;
    }
}
