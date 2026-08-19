package com.example.vatica.knowledge;

import java.util.List;

import com.example.vatica.auth.RequestIdentity;

public interface KnowledgeVectorIndex {

    void upsert(KnowledgeDocumentRecord document, KnowledgeChunkRecord chunk, float[] vector);

    void deleteDocument(long documentId);

    List<Match> search(RequestIdentity identity, float[] vector, int topK);

    record Match(long chunkId, double score) {
    }
}
