package com.example.vatica.knowledge;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
    private static final String METADATA_TABLE = "vatica_knowledge_index_meta";
    private static final String SCHEMA_VERSION = "pgvector-v1";
    private static final String INDEX_NAME = "idx_vatica_knowledge_vector_cosine";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final KnowledgeProperties properties;
    private final Map<Long, StoredVector> fallback = new ConcurrentHashMap<>();
    private volatile boolean postgres;
    private volatile boolean ready;
    private volatile String indexVersion = "unknown";
    private volatile Readiness readiness = Readiness.unavailable(false, "向量索引尚未初始化。");

    /**
     * 迭代 27A：把 pgvector 的扩展、索引、Schema 和 Embedding 配置指纹作为可审计就绪状态。
     * 不返回数据库地址、账号或 API Key；H2 只标记为本地回退，不伪装成生产 pgvector。
     */
    public record Readiness(boolean ready, boolean postgres, boolean extensionInstalled, boolean indexReady,
            String extensionVersion, String schemaVersion, String embeddingProvider, String embeddingModel,
            int vectorDimensions, String configFingerprint, String message) {
        public Readiness(boolean ready, boolean postgres) {
            this(ready, postgres, postgres && ready, postgres && ready, null,
                    postgres ? SCHEMA_VERSION : "h2-fallback-v1", null, null, 0, null, null);
        }

        static Readiness unavailable(boolean postgres, String message) {
            return new Readiness(false, postgres, false, false, null, null, null, null, 0, null, message);
        }
    }

    public JdbcKnowledgeVectorIndex(JdbcTemplate jdbc, DataSource dataSource, KnowledgeProperties properties) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.properties = properties;
        initialize();
    }

    public Readiness readiness() {
        return readiness;
    }

    @Override
    public String indexVersion() {
        return indexVersion;
    }

    private void initialize() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            postgres = product.contains("postgres");
            if (product.contains("h2")) {
                ready = true;
                indexVersion = "h2-fallback-v1";
                readiness = new Readiness(true, false, false, false, null, "h2-fallback-v1",
                        properties.embeddingProvider(), embeddingModel(), properties.vectorDimensions(),
                        configFingerprint(), "当前使用 H2 进程内向量回退，仅用于测试或离线开发。 ");
                return;
            }
            if (!postgres) {
                indexVersion = "unavailable";
                log.error("知识库向量索引只支持 PostgreSQL/pgvector；当前数据库为 {}。", product);
                readiness = Readiness.unavailable(false, "当前数据库不是 PostgreSQL，无法启用 pgvector。 ");
                return;
            }
            String extensionVersion = extensionVersion();
            if (extensionVersion == null) {
                indexVersion = "unavailable";
                readiness = Readiness.unavailable(true, "PostgreSQL 未安装 vector 扩展，知识检索保持不可用。 ");
                return;
            }
            if (!tableExists(TABLE) || !tableExists(METADATA_TABLE)) {
                indexVersion = SCHEMA_VERSION;
                readiness = new Readiness(false, true, true, false, extensionVersion, SCHEMA_VERSION,
                        properties.embeddingProvider(), embeddingModel(), properties.vectorDimensions(),
                        configFingerprint(), "pgvector 扩展已安装，但知识库迁移尚未执行。请先执行 pgvector-index-v1.sql。 ");
                return;
            }
            String fingerprint = configFingerprint();
            Map<String, Object> metadata = readMetadata();
            if (metadata.isEmpty()) {
                indexVersion = SCHEMA_VERSION;
                readiness = new Readiness(false, true, true, false, extensionVersion, SCHEMA_VERSION,
                        properties.embeddingProvider(), embeddingModel(), properties.vectorDimensions(), fingerprint,
                        "知识库索引元数据缺失，请执行迁移并写入实际 Embedding 配置。 ");
                return;
            } else if (!metadataMatches(metadata, fingerprint)) {
                indexVersion = metadataIndexVersion(metadata);
                readiness = new Readiness(false, true, true, false, extensionVersion, SCHEMA_VERSION,
                        properties.embeddingProvider(), embeddingModel(), properties.vectorDimensions(), fingerprint,
                        "Embedding 配置或索引版本已变化，必须重建知识库索引后才能检索。 ");
                return;
            }
            ready = true;
            indexVersion = metadataIndexVersion(metadata);
            boolean indexReady = hasIndex();
            readiness = new Readiness(true, true, true, indexReady, extensionVersion, SCHEMA_VERSION,
                    properties.embeddingProvider(), embeddingModel(), properties.vectorDimensions(), fingerprint,
                    indexReady ? "PostgreSQL pgvector 扩展、Schema 和 HNSW 索引已就绪。"
                            : "PostgreSQL pgvector 已就绪，但 HNSW 索引不可用，将使用精确余弦检索。 ");
        } catch (Exception e) {
            log.error("pgvector 表初始化失败，知识库检索暂不可用：{}", e.getMessage());
            ready = false;
            indexVersion = "unavailable";
            readiness = Readiness.unavailable(postgres, "pgvector Schema 检查失败，知识检索暂不可用。 ");
        }
    }

    private String extensionVersion() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'");
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("extversion"));
    }

    private Map<String, Object> readMetadata() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT schema_version, embedding_provider, embedding_model, "
                + "vector_dimensions, index_version, config_fingerprint FROM " + METADATA_TABLE + " WHERE id = 1");
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private boolean tableExists(String table) {
        return !jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_name = ?", table).isEmpty();
    }

    private boolean metadataMatches(Map<String, Object> metadata, String fingerprint) {
        return SCHEMA_VERSION.equals(String.valueOf(metadata.get("schema_version")))
                && SCHEMA_VERSION.equals(String.valueOf(metadata.get("index_version")))
                && properties.embeddingProvider().equals(String.valueOf(metadata.get("embedding_provider")))
                && embeddingModel().equals(String.valueOf(metadata.get("embedding_model")))
                && properties.vectorDimensions() == ((Number) metadata.get("vector_dimensions")).intValue()
                && fingerprint.equals(String.valueOf(metadata.get("config_fingerprint")));
    }

    private static String metadataIndexVersion(Map<String, Object> metadata) {
        Object value = metadata.get("index_version");
        return value == null || String.valueOf(value).isBlank() ? SCHEMA_VERSION : String.valueOf(value);
    }

    private boolean hasIndex() {
        return !jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                INDEX_NAME).isEmpty();
    }

    private String embeddingModel() {
        return "openai".equals(properties.embeddingProvider()) ? properties.openai().model() : "local-hash";
    }

    private String configFingerprint() {
        String value = properties.embeddingProvider() + "|" + embeddingModel() + "|"
                + properties.vectorDimensions() + "|" + properties.chunkSize() + "|" + properties.chunkOverlap()
                + "|" + SCHEMA_VERSION;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("操作失败：无法计算知识库配置指纹。", e);
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
