package com.example.vatica.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.permission.FileSandboxPolicy;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 知识库事实源：文档生命周期、租户权限、切片元数据和引用映射均由 Vatica 管理。 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final FileSandboxPolicy sandboxPolicy;
    private final KnowledgeTextChunker chunker;
    private final KnowledgeEmbeddingService embeddingService;
    private final KnowledgeVectorIndex vectorIndex;
    private final KnowledgeProperties properties;

    public KnowledgeBaseService(KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository, FileSandboxPolicy sandboxPolicy,
            KnowledgeTextChunker chunker, KnowledgeEmbeddingService embeddingService,
            KnowledgeVectorIndex vectorIndex, KnowledgeProperties properties) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.sandboxPolicy = sandboxPolicy;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.vectorIndex = vectorIndex;
        this.properties = properties;
    }

    @Transactional
    public DocumentView importDocument(String rawPath, KnowledgeVisibility visibility) {
        if (!properties.enabled()) {
            throw new IllegalStateException("操作失败：知识库功能当前未启用。");
        }
        RequestIdentity identity = RequestIdentityContext.require();
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("操作失败：知识库导入路径不能为空。");
        }
        KnowledgeVisibility scope = visibility == null ? KnowledgeVisibility.PRIVATE : visibility;
        Path path = sandboxPolicy.resolveForRead(rawPath.trim(), "知识库导入需要读取该文件");
        String sourcePath = normalizedSourcePath(rawPath);
        KnowledgeDocumentRecord document = documentRepository
                .findByOrgIdAndUserIdAndSourcePath(identity.orgId(), identity.userId(), sourcePath)
                .orElseGet(() -> new KnowledgeDocumentRecord(identity.orgId(), identity.userId(), scope,
                        sourcePath, fileName(path), ""));
        return indexDocument(document, path, scope, IndexMode.IMPORT);
    }

    /** 失败或中断的索引从授权原路径重新开始；不会绕过当前用户的工作区权限。 */
    @Transactional
    public DocumentView retryDocument(long id) {
        return reindexOwnedDocument(id, false);
    }

    /** 显式重建即使 READY 文档也会重新读取、切片和写入向量，版本号递增。 */
    @Transactional
    public DocumentView rebuildDocument(long id) {
        return reindexOwnedDocument(id, true);
    }

    private DocumentView reindexOwnedDocument(long id, boolean force) {
        if (!properties.enabled()) {
            throw new IllegalStateException("操作失败：知识库功能当前未启用。");
        }
        RequestIdentity identity = RequestIdentityContext.require();
        KnowledgeDocumentRecord document = documentRepository.findByIdAndOrgIdAndUserId(id,
                identity.orgId(), identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：知识库文档不存在或无权操作。"));
        if (!force && document.getStatus() == KnowledgeDocumentStatus.READY) {
            throw new IllegalStateException("操作失败：文档已经就绪；如需重新生成索引，请使用重建操作。 ");
        }
        Path path = sandboxPolicy.resolveForRead(document.getSourcePath(), "知识库重试需要读取原授权文件");
        return indexDocument(document, path, document.getVisibility(), force ? IndexMode.REBUILD : IndexMode.RETRY);
    }

    private DocumentView indexDocument(KnowledgeDocumentRecord document, Path path,
            KnowledgeVisibility scope, IndexMode mode) {
        String text = readText(path);
        if (text.isBlank()) {
            throw new IllegalArgumentException("操作失败：知识库只支持导入非空文本或 Word 文档。");
        }
        String hash = sha256(text.getBytes(StandardCharsets.UTF_8));
        if (mode == IndexMode.IMPORT && document.getContentHash().equals(hash)
                && document.getVisibility() == scope
                && document.getStatus() == KnowledgeDocumentStatus.READY) {
            return view(document, chunkRepository.findByDocumentIdOrderByOrdinal(document.getId()).size());
        }
        List<KnowledgeTextChunker.Chunk> chunks = chunker.chunk(text, properties);
        if (document.getId() != null) {
            // 先清理旧向量和切片，保证一次文档只有一套可检索索引。
            vectorIndex.deleteDocument(document.getId());
            chunkRepository.deleteByDocumentId(document.getId());
            if (mode == IndexMode.RETRY) {
                document.beginRetry(hash, scope, fileName(path));
            } else {
                document.beginReindex(hash, scope, fileName(path));
            }
        }
        if (document.getId() == null) {
            document.beginInitialIndex(hash, scope, fileName(path), chunks.size());
        } else {
            document.beginIndexing(chunks.size());
        }
        document = documentRepository.save(document);
        if (chunks.isEmpty()) {
            document.failed("文档没有可索引的文本片段");
            documentRepository.save(document);
            return view(document, 0);
        }
        try {
            int ordinal = 0;
            for (KnowledgeTextChunker.Chunk value : chunks) {
                KnowledgeChunkRecord chunk = chunkRepository.save(new KnowledgeChunkRecord(document.getId(), ordinal++,
                        value.text(), value.heading(), value.startOffset(), value.endOffset()));
                vectorIndex.upsert(document, chunk, embeddingService.embed(value.text()));
                document.markChunkIndexed(ordinal);
                documentRepository.save(document);
            }
            document.ready();
            documentRepository.save(document);
        } catch (RuntimeException e) {
            // PostgreSQL 语句失败后当前事务已经不可继续，交给事务拦截器整体回滚。
            if (e instanceof DataAccessException) {
                throw e;
            }
            vectorIndex.deleteDocument(document.getId());
            chunkRepository.deleteByDocumentId(document.getId());
            document.failed(rootMessage(e));
            documentRepository.save(document);
        }
        return view(document, document.getStatus() == KnowledgeDocumentStatus.READY ? chunks.size() : 0);
    }

    private enum IndexMode { IMPORT, RETRY, REBUILD }

    @Transactional(readOnly = true)
    public List<DocumentView> listDocuments() {
        RequestIdentity identity = RequestIdentityContext.require();
        return documentRepository.findVisible(identity.orgId(), identity.userId()).stream()
                .map(document -> view(document, chunkRepository.findByDocumentIdOrderByOrdinal(document.getId()).size()))
                .toList();
    }

    @Transactional
    public void deleteDocument(long id) {
        RequestIdentity identity = RequestIdentityContext.require();
        KnowledgeDocumentRecord document = documentRepository.findByIdAndOrgIdAndUserId(id, identity.orgId(), identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：知识库文档不存在或无权删除。"));
        vectorIndex.deleteDocument(document.getId());
        chunkRepository.deleteByDocumentId(document.getId());
        documentRepository.delete(document);
    }

    public SearchResult search(String query, int topK) {
        if (!properties.enabled()) {
            throw new IllegalStateException("操作失败：知识库功能当前未启用。");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("操作失败：知识库检索词不能为空。");
        }
        if (query.trim().length() > 500) {
            throw new IllegalArgumentException("操作失败：知识库检索词不能超过 500 个字符。");
        }
        RequestIdentity identity = RequestIdentityContext.require();
        List<KnowledgeVectorIndex.Match> matches = vectorIndex.search(identity,
                embeddingService.embed(query.trim()), Math.max(1, Math.min(topK, 8)));
        Map<Long, KnowledgeChunkRecord> chunks = chunkRepository.findAllById(matches.stream()
                .map(KnowledgeVectorIndex.Match::chunkId).toList()).stream()
                .collect(Collectors.toMap(KnowledgeChunkRecord::getId, Function.identity()));
        Map<Long, KnowledgeDocumentRecord> documents = documentRepository.findAllById(chunks.values().stream()
                .map(KnowledgeChunkRecord::getDocumentId).distinct().toList()).stream()
                .collect(Collectors.toMap(KnowledgeDocumentRecord::getId, Function.identity()));
        int used = 0;
        java.util.ArrayList<Citation> citations = new java.util.ArrayList<>();
        int citationNumber = 1;
        String indexVersion = vectorIndex.indexVersion();
        for (KnowledgeVectorIndex.Match match : matches) {
            KnowledgeChunkRecord chunk = chunks.get(match.chunkId());
            KnowledgeDocumentRecord document = chunk == null ? null : documents.get(chunk.getDocumentId());
            if (chunk == null || document == null || !visible(identity, document) || document.getStatus() != KnowledgeDocumentStatus.READY) {
                continue;
            }
            if (used + chunk.getText().length() > properties.maxSearchChars()) {
                break;
            }
            citations.add(new Citation("C" + citationNumber++, document.getId(), document.getSourceName(),
                    document.getSourcePath(), chunk.getId(), chunk.getHeading(), chunk.getStartOffset(),
                    chunk.getEndOffset(), round(match.score()), chunk.getText(), sourceLocation(chunk),
                    snippet(chunk.getText()), document.getVersion(), indexVersion, accessScope(identity, document)));
            used += chunk.getText().length();
        }
        return new SearchResult(query.trim(), indexVersion, "CURRENT_USER_PRIVATE_AND_ORG_SHARED", List.copyOf(citations));
    }

    private static boolean visible(RequestIdentity identity, KnowledgeDocumentRecord document) {
        return identity.orgId().equals(document.getOrgId())
                && (identity.userId().equals(document.getUserId())
                        || document.getVisibility() == KnowledgeVisibility.ORG_SHARED);
    }

    private String readText(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("操作失败：知识库导入路径必须是存在的文件。");
            }
            long size = Files.size(path);
            if (size > properties.maxDocumentBytes()) {
                throw new IllegalArgumentException("操作失败：文档超过知识库大小上限（" + properties.maxDocumentBytes() + " 字节）。");
            }
            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".docx")) {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(Files.readAllBytes(path)))) {
                    return document.getParagraphs().stream().map(p -> p.getText()).filter(t -> t != null && !t.isBlank())
                            .collect(Collectors.joining("\n"));
                }
            }
            if (!(name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown"))) {
                throw new IllegalArgumentException("操作失败：知识库第一版只支持 .txt、.md、.markdown、.docx。");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：读取知识库文档失败。" + e.getMessage(), e);
        }
    }

    private static String normalizedSourcePath(String rawPath) {
        return rawPath.trim().replace('\\', '/');
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? "未命名文档" : path.getFileName().toString();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：无法计算文档指纹。", e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static DocumentView view(KnowledgeDocumentRecord document, int chunks) {
        int total = document.getTotalChunks() > 0 ? document.getTotalChunks() : chunks;
        int indexed = document.getStatus() == KnowledgeDocumentStatus.READY
                ? total : Math.min(document.getIndexedChunks(), chunks);
        int progress = total == 0 ? 0 : Math.min(100, Math.max(0, (indexed * 100) / total));
        return new DocumentView(document.getId(), document.getSourceName(), document.getSourcePath(),
                document.getVisibility(), document.getContentHash(), document.getVersion(), document.getStatus(),
                chunks, total, indexed, progress, document.getIndexAttempt(),
                document.getErrorMessage(), document.getUpdatedAt().toString());
    }

    /** 引用位置统一使用一基字符范围；文档解析器无法可靠提供页码时用章节和字符位置替代。 */
    private static String sourceLocation(KnowledgeChunkRecord chunk) {
        String section = chunk.getHeading() == null || chunk.getHeading().isBlank()
                ? "文档正文" : "章节：“" + chunk.getHeading() + "”";
        return section + " · 字符 " + (chunk.getStartOffset() + 1) + "-" + chunk.getEndOffset();
    }

    private static String snippet(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 280) + "...";
    }

    /** 不暴露任意用户或组织标识，仅说明本条结果通过何种权限路径被当前请求者读取。 */
    private static String accessScope(RequestIdentity identity, KnowledgeDocumentRecord document) {
        return identity.userId().equals(document.getUserId()) ? "CURRENT_USER_OWNER" : "ORGANIZATION_SHARED";
    }

    public record DocumentView(Long id, String sourceName, String sourcePath, KnowledgeVisibility visibility,
            String contentHash, int version, KnowledgeDocumentStatus status, int chunkCount, int totalChunks,
            int indexedChunks, int progressPercent, int indexAttempt, String errorMessage, String updatedAt) {
        /** 保持 19B/26D 测试和调用方的构造兼容；新字段按已完成切片推导。 */
        public DocumentView(Long id, String sourceName, String sourcePath, KnowledgeVisibility visibility,
                String contentHash, int version, KnowledgeDocumentStatus status, int chunkCount,
                String errorMessage, String updatedAt) {
            this(id, sourceName, sourcePath, visibility, contentHash, version, status, chunkCount, chunkCount,
                    status == KnowledgeDocumentStatus.READY ? chunkCount : 0,
                    status == KnowledgeDocumentStatus.READY ? 100 : 0, 1, errorMessage, updatedAt);
        }
    }

    public record SearchResult(String query, String indexVersion, String accessScope, List<Citation> citations) {
        public SearchResult(String query, List<Citation> citations) {
            this(query, "unknown", "CURRENT_USER_PRIVATE_AND_ORG_SHARED", citations);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(String citationId, Long documentId, String documentName, String sourcePath,
            Long chunkId, String heading, int startOffset, int endOffset, double score, String quote,
            String sourceLocation, String snippet, int documentVersion, String indexVersion, String accessScope) {
        public Citation(String citationId, Long documentId, String documentName, String sourcePath,
                Long chunkId, String heading, int startOffset, int endOffset, double score, String quote,
                String sourceLocation, String snippet, int documentVersion, String indexVersion, String accessScope) {
            this.citationId = citationId;
            this.documentId = documentId;
            this.documentName = documentName;
            this.sourcePath = sourcePath;
            this.chunkId = chunkId;
            this.heading = heading;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.score = score;
            this.quote = quote;
            this.sourceLocation = sourceLocation;
            this.snippet = snippet;
            this.documentVersion = documentVersion;
            this.indexVersion = indexVersion;
            this.accessScope = accessScope;
        }

        public Citation(String citationId, Long documentId, String documentName, String sourcePath,
                Long chunkId, String heading, int startOffset, int endOffset, double score, String quote) {
            this(citationId, documentId, documentName, sourcePath, chunkId, heading, startOffset, endOffset, score,
                    quote, (heading == null || heading.isBlank() ? "文档正文" : "章节：“" + heading + "”")
                            + " · 字符 " + (startOffset + 1) + "-" + endOffset,
                    KnowledgeBaseService.snippet(quote), 1, "unknown", "CURRENT_USER_PRIVATE_AND_ORG_SHARED");
        }
    }
}
