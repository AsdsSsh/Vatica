package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnowledgeDocumentRecordTest {

    @Test
    void tracksIndexProgressAndKeepsRetryOnSameContentVersion() {
        KnowledgeDocumentRecord document = new KnowledgeDocumentRecord(9L, 7L,
                KnowledgeVisibility.PRIVATE, "docs/guide.md", "guide.md", "hash");

        document.beginInitialIndex("hash", KnowledgeVisibility.PRIVATE, "guide.md", 4);
        document.markChunkIndexed(2);

        assertThat(document.getVersion()).isEqualTo(1);
        assertThat(document.getContentHash()).isEqualTo("hash");
        assertThat(document.getIndexAttempt()).isEqualTo(1);
        assertThat(document.getTotalChunks()).isEqualTo(4);
        assertThat(document.getIndexedChunks()).isEqualTo(2);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.INDEXING);

        document.failed("embedding 暂时不可用");
        document.beginRetry("hash", KnowledgeVisibility.PRIVATE, "guide.md");
        assertThat(document.getVersion()).isEqualTo(1);
        assertThat(document.getIndexAttempt()).isEqualTo(2);
        assertThat(document.getIndexedChunks()).isZero();
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.INDEXING);

        document.beginReindex("hash-2", KnowledgeVisibility.ORG_SHARED, "guide-v2.md");
        assertThat(document.getVersion()).isEqualTo(2);
        assertThat(document.getIndexAttempt()).isEqualTo(3);
        assertThat(document.getVisibility()).isEqualTo(KnowledgeVisibility.ORG_SHARED);
    }
}
