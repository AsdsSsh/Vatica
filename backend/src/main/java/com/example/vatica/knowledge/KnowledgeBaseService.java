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
        String text = readText(path);
        if (text.isBlank()) {
            throw new IllegalArgumentException("操作失败：知识库只支持导入非空文本或 Word 文档。");
        }
        String hash = sha256(text.getBytes(StandardCharsets.UTF_8));
        KnowledgeDocumentRecord document = documentRepository
                .findByOrgIdAndUserIdAndSourcePath(identity.orgId(), identity.userId(), sourcePath)
                .orElseGet(() -> new KnowledgeDocumentRecord(identity.orgId(), identity.userId(), scope,
                        sourcePath, fileName(path), hash));
        if (document.getId() != null && document.getContentHash().equals(hash)
                && document.getVisibility() == scope && document.getStatus() == KnowledgeDocumentStatus.READY) {
            return view(document, chunkRepository.findByDocumentIdOrderByOrdinal(document.getId()).size());
        }
        if (document.getId() != null) {
            vectorIndex.deleteDocument(document.getId());
            chunkRepository.deleteByDocumentId(document.getId());
            document.beginReindex(hash, scope, fileName(path));
        }
        document = documentRepository.save(document);

        List<KnowledgeTextChunker.Chunk> chunks = chunker.chunk(text, properties);
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
                    chunk.getEndOffset(), round(match.score()), chunk.getText()));
            used += chunk.getText().length();
        }
        return new SearchResult(query.trim(), List.copyOf(citations));
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
        return new DocumentView(document.getId(), document.getSourceName(), document.getSourcePath(),
                document.getVisibility(), document.getContentHash(), document.getVersion(), document.getStatus(),
                chunks, document.getErrorMessage(), document.getUpdatedAt().toString());
    }

    public record DocumentView(Long id, String sourceName, String sourcePath, KnowledgeVisibility visibility,
            String contentHash, int version, KnowledgeDocumentStatus status, int chunkCount, String errorMessage,
            String updatedAt) {
    }

    public record SearchResult(String query, List<Citation> citations) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(String citationId, Long documentId, String documentName, String sourcePath,
            Long chunkId, String heading, int startOffset, int endOffset, double score, String quote) {
    }
}
