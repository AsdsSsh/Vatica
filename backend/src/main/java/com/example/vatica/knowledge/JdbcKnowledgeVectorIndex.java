package com.example.vatica.knowledge;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;

/**
 * 迭代 19B：pgvector 的最小适配器。
 *
 * <p>没有把业务状态交给 VectorStore 自动配置：文档权限条件由本类写进 SQL，
 * chunk 元数据仍由 JPA 管理。H2 测试环境使用进程内向量回退，不执行 PostgreSQL 专用 SQL。
 */
@Service
public class JdbcKnowledgeVectorIndex implements KnowledgeVectorIndex {

    private static final Logger log = LoggerFactory.getLogger(JdbcKnowledgeVectorIndex.class);
    private static final String TABLE = "vatica_knowledge_vector";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final KnowledgeProperties properties;
    private final Map<Long, StoredVector> fallback = new ConcurrentHashMap<>();
    private volatile boolean postgres;
    private volatile boolean ready;

    public JdbcKnowledgeVectorIndex(JdbcTemplate jdbc, DataSource dataSource, KnowledgeProperties properties) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.properties = properties;
        initialize();
    }

    private void initialize() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            postgres = product.contains("postgres");
            if (product.contains("h2")) {
                ready = true;
                return;
            }
            if (!postgres) {
                log.error("知识库向量索引只支持 PostgreSQL/pgvector；当前数据库为 {}。", product);
                return;
            }
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "chunk_id BIGINT PRIMARY KEY, document_id BIGINT NOT NULL, "
                    + "org_id BIGINT NOT NULL, user_id BIGINT NOT NULL, visibility VARCHAR(20) NOT NULL, "
                    + "embedding vector(" + properties.vectorDimensions() + ") NOT NULL)");
            try {
                jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vatica_knowledge_vector_cosine ON " + TABLE
                        + " USING hnsw (embedding vector_cosine_ops)");
            } catch (Exception indexError) {
                // 旧版 pgvector 可能没有 HNSW；精确排序仍可用，索引优化不阻塞业务。
                log.warn("知识库 HNSW 索引创建失败，将继续使用精确余弦检索：{}", indexError.getMessage());
            }
            ready = true;
        } catch (Exception e) {
            log.error("pgvector 表初始化失败，知识库检索暂不可用：{}", e.getMessage());
        }
    }

    @Override
    public void upsert(KnowledgeDocumentRecord document, KnowledgeChunkRecord chunk, float[] vector) {
        validate(vector);
        if (document.getId() == null || chunk.getId() == null) {
            throw new IllegalArgumentException("操作失败：知识库文档和切片必须先持久化。");
        }
        ensureReady();
        if (!postgres) {
            fallback.put(chunk.getId(), new StoredVector(document.getId(), document.getOrgId(), document.getUserId(),
                    document.getVisibility(), vector.clone()));
            return;
        }
        jdbc.update("DELETE FROM " + TABLE + " WHERE chunk_id = ?", chunk.getId());
        jdbc.update("INSERT INTO " + TABLE
                + " (chunk_id, document_id, org_id, user_id, visibility, embedding) "
                + "VALUES (?, ?, ?, ?, ?, CAST(? AS vector))",
                chunk.getId(), document.getId(), document.getOrgId(), document.getUserId(),
                document.getVisibility().name(), vectorLiteral(vector));
    }

    @Override
    public void deleteDocument(long documentId) {
        ensureReady();
        if (!postgres) {
            fallback.entrySet().removeIf(entry -> entry.getValue().documentId() == documentId);
            return;
        }
        jdbc.update("DELETE FROM " + TABLE + " WHERE document_id = ?", documentId);
    }

    @Override
    public List<Match> search(RequestIdentity identity, float[] vector, int topK) {
        validate(vector);
        ensureReady();
        int limit = Math.max(1, Math.min(topK, 8));
        if (!postgres) {
            return fallback.entrySet().stream()
                    .filter(entry -> visible(identity, entry.getValue()))
                    .map(entry -> new Match(entry.getKey(), cosine(vector, entry.getValue().vector())))
                    .sorted(Comparator.comparingDouble(Match::score).reversed())
                    .limit(limit)
                    .toList();
        }
        String literal = vectorLiteral(vector);
        String sql = "SELECT chunk_id, 1 - (embedding <=> CAST(? AS vector)) AS score FROM " + TABLE
                + " WHERE org_id = ? AND (user_id = ? OR visibility = 'ORG_SHARED')"
                + " ORDER BY embedding <=> CAST(? AS vector) LIMIT ?";
        return jdbc.query(sql, (rs, rowNum) -> new Match(rs.getLong("chunk_id"), rs.getDouble("score")),
                literal, identity.orgId(), identity.userId(), literal, limit);
    }

    private void ensureReady() {
        if (!ready) {
            throw new IllegalStateException("操作失败：PostgreSQL pgvector 尚未就绪，请确认 vector 扩展和数据库连接。");
        }
    }

    private void validate(float[] vector) {
        if (vector == null || vector.length != properties.vectorDimensions()) {
            throw new IllegalArgumentException("操作失败：向量维度不符合知识库配置。");
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("操作失败：Embedding 向量包含非法数值。");
            }
            norm += value * value;
        }
        if (norm == 0) {
            throw new IllegalArgumentException("操作失败：Embedding 向量不能为零向量。");
        }
    }

    private static boolean visible(RequestIdentity identity, StoredVector value) {
        return identity != null && identity.orgId().equals(value.orgId())
                && (identity.userId().equals(value.userId()) || value.visibility() == KnowledgeVisibility.ORG_SHARED);
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String vectorLiteral(float[] vector) {
        List<String> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(Float.toString(value));
        }
        return "[" + String.join(",", values) + "]";
    }

    private record StoredVector(long documentId, long orgId, long userId,
            KnowledgeVisibility visibility, float[] vector) {
    }
}
