package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.permission.FileSandboxPolicy;

class KnowledgeBaseServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void searchReturnsTraceableCitationAndRechecksVisibility() {
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeVectorIndex vectors = mock(KnowledgeVectorIndex.class);
        KnowledgeEmbeddingService embeddings = text -> new float[] { 1, 0, 0 };
        KnowledgeProperties properties = new KnowledgeProperties(
                true, "local-hash", 3, 1024 * 1024, 100, 20, 2000);
        KnowledgeBaseService service = new KnowledgeBaseService(documents, chunks, mock(FileSandboxPolicy.class),
                new KnowledgeTextChunker(), embeddings, vectors, properties);
        RequestIdentity identity = new RequestIdentity(7L, 9L, "USER", "alice");
        RequestIdentityContext.set(identity);

        KnowledgeChunkRecord visibleChunk = chunk(11L, 21L, "权限策略原文", 10, 16);
        KnowledgeChunkRecord hiddenChunk = chunk(12L, 22L, "其他用户私有内容", 20, 28);
        KnowledgeDocumentRecord visibleDocument = document(21L, 9L, 7L, KnowledgeVisibility.PRIVATE, "权限.md");
        KnowledgeDocumentRecord hiddenDocument = document(22L, 9L, 8L, KnowledgeVisibility.PRIVATE, "私有.md");
        when(vectors.search(eq(identity), any(float[].class), eq(5))).thenReturn(List.of(
                new KnowledgeVectorIndex.Match(11L, 0.91), new KnowledgeVectorIndex.Match(12L, 0.99)));
        when(chunks.findAllById(any())).thenReturn(List.of(visibleChunk, hiddenChunk));
        when(documents.findAllById(any())).thenReturn(List.of(visibleDocument, hiddenDocument));

        KnowledgeBaseService.SearchResult result = service.search("权限怎么控制", 5);

        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.citationId()).isEqualTo("C1");
            assertThat(citation.documentName()).isEqualTo("权限.md");
            assertThat(citation.sourcePath()).isEqualTo("docs/权限.md");
            assertThat(citation.startOffset()).isEqualTo(10);
            assertThat(citation.quote()).isEqualTo("权限策略原文");
        });
    }

    @Test
    void importIndexesEveryPersistedChunkAndMarksDocumentReady() throws Exception {
        Path source = tempDir.resolve("guide.md");
        Files.writeString(source, "# 知识库\n" + "权限隔离、引用追踪和索引一致性。".repeat(20));
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeVectorIndex vectors = mock(KnowledgeVectorIndex.class);
        KnowledgeEmbeddingService embeddings = text -> new float[] { 1, 0, 0 };
        FileSandboxPolicy sandbox = mock(FileSandboxPolicy.class);
        KnowledgeProperties properties = new KnowledgeProperties(
                true, "local-hash", 3, 1024 * 1024, 100, 20, 2000);
        KnowledgeBaseService service = new KnowledgeBaseService(documents, chunks, sandbox,
                new KnowledgeTextChunker(), embeddings, vectors, properties);
        RequestIdentityContext.set(new RequestIdentity(7L, 9L, "USER", "alice"));
        when(sandbox.resolveForRead(eq("guide.md"), anyString())).thenReturn(source);
        when(documents.findByOrgIdAndUserIdAndSourcePath(9L, 7L, "guide.md")).thenReturn(Optional.empty());
        when(documents.save(any(KnowledgeDocumentRecord.class))).thenAnswer(invocation -> {
            KnowledgeDocumentRecord value = invocation.getArgument(0);
            if (value.getId() == null) {
                ReflectionTestUtils.setField(value, "id", 21L);
            }
            return value;
        });
        AtomicLong chunkIds = new AtomicLong(100);
        when(chunks.save(any(KnowledgeChunkRecord.class))).thenAnswer(invocation -> {
            KnowledgeChunkRecord value = invocation.getArgument(0);
            ReflectionTestUtils.setField(value, "id", chunkIds.incrementAndGet());
            return value;
        });

        KnowledgeBaseService.DocumentView result = service.importDocument("guide.md", KnowledgeVisibility.PRIVATE);

        assertThat(result.status()).isEqualTo(KnowledgeDocumentStatus.READY);
        assertThat(result.chunkCount()).isGreaterThan(1);
        verify(vectors, times(result.chunkCount())).upsert(any(KnowledgeDocumentRecord.class),
                any(KnowledgeChunkRecord.class), any(float[].class));
    }

    private static KnowledgeChunkRecord chunk(long id, long documentId, String text, int start, int end) {
        KnowledgeChunkRecord chunk = new KnowledgeChunkRecord(documentId, 0, text, "权限", start, end);
        ReflectionTestUtils.setField(chunk, "id", id);
        return chunk;
    }

    private static KnowledgeDocumentRecord document(long id, long orgId, long userId,
            KnowledgeVisibility visibility, String name) {
        KnowledgeDocumentRecord document = new KnowledgeDocumentRecord(orgId, userId, visibility,
                "docs/" + name, name, "hash");
        ReflectionTestUtils.setField(document, "id", id);
        document.ready();
        return document;
    }
}
