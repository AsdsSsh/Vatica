package com.example.vatica.knowledge;

import java.util.List;

import com.example.vatica.auth.RequestIdentity;

public interface KnowledgeVectorIndex {

    void upsert(KnowledgeDocumentRecord document, KnowledgeChunkRecord chunk, float[] vector);

    void deleteDocument(long documentId);

    List<Match> search(RequestIdentity identity, float[] vector, int topK);

    /** 当前参与检索的向量索引版本；只用于引用核对，不返回基础设施连接信息。 */
    default String indexVersion() {
        return "unknown";
    }

    record Match(long chunkId, double score) {
    }
}
