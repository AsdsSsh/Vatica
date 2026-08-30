package com.example.vatica.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 迭代 29B：受控关键事实服务。
 *
 * <p>事实值是可重建上下文的短索引，不是任意 JSON 存储。服务层统一校验大小、层级、
 * 字段和租户；所有变更通过新 revision 完成，旧版本不会被覆盖。</p>
 */
@Service
public class ContextFactService {

    public static final int MAX_VALUE_JSON_CHARS = 4_000;
    public static final int MAX_EVIDENCE_JSON_CHARS = 4_000;
    public static final int MAX_DISPLAY_SUMMARY_CHARS = 500;

    private static final int MAX_JSON_DEPTH = 4;
    private static final int MAX_OBJECT_FIELDS = 24;
    private static final int MAX_ARRAY_ITEMS = 16;
    private static final int MAX_STRING_CHARS = 1_000;
    private static final Pattern FACT_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}");
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key", "authorization", "cookie",
            "prompt", "systemprompt", "raw", "content", "body", "quote", "reasoning", "thought",
            "chainofthought", "stacktrace");

    private final ContextFactRecordRepository repository;
    private final ObjectMapper mapper;

    public ContextFactService(ContextFactRecordRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** 绑定当前请求租户的事实；控制器和同步业务入口使用此方法。 */
    @Transactional
    public ContextFactRecord capture(CaptureRequest request) {
        return capture(RequestIdentityContext.require(), request);
    }

    /** 异步任务持有身份快照时使用；仍然强制校验 userId/orgId。 */
    @Transactional
    public ContextFactRecord capture(RequestIdentity identity, CaptureRequest request) {
        Tenant tenant = tenant(identity);
        Normalized normalized = normalize(request);
        ContextFactRecord latest = repository
                .findTopByOrgIdAndUserIdAndScopeTypeAndScopeIdAndFactKeyOrderByRevisionDesc(
                        tenant.orgId(), tenant.userId(), normalized.scopeType(), normalized.scopeId(),
                        normalized.factKey())
                .orElse(null);
        if (latest != null && latest.getStatus() == ContextFactStatus.ACTIVE && equivalent(latest, normalized)) {
            return latest;
        }

        int nextRevision = latest == null ? 1 : latest.getRevision() + 1;
        String id = java.util.UUID.randomUUID().toString();
        if (latest != null && latest.getStatus() == ContextFactStatus.ACTIVE) {
            latest.supersede(id);
            repository.save(latest);
        }
        ContextFactRecord created = new ContextFactRecord(id, tenant.orgId(), tenant.userId(),
                normalized.scopeType(), normalized.scopeId(), normalized.subjectType(), normalized.subjectId(),
                normalized.factKey(), nextRevision, latest == null ? null : latest.getId(), normalized.factType(),
                normalized.valueJson(), normalized.displaySummary(), normalized.valueHash(), normalized.trustLevel(),
                normalized.verificationState(), normalized.sourceType(), normalized.sourceId(), normalized.sourceVersion(),
                normalized.sourceFingerprint(), normalized.evidenceRefsJson(), normalized.observedAt(),
                normalized.verifiedAt(), normalized.validUntil());
        return repository.save(created);
    }

    /** 当前租户某个上下文范围内仍为 ACTIVE 的事实版本。 */
    @Transactional(readOnly = true)
    public List<ContextFactRecord> listActive(ContextFactScopeType scopeType, String scopeId) {
        Tenant tenant = tenant(RequestIdentityContext.require());
        return listActive(tenant.identity(), scopeType, scopeId);
    }

    @Transactional(readOnly = true)
    public List<ContextFactRecord> listActive(RequestIdentity identity, ContextFactScopeType scopeType,
            String scopeId) {
        Tenant tenant = tenant(identity);
        validateScope(scopeType, scopeId);
        return repository.findByOrgIdAndUserIdAndScopeTypeAndScopeIdAndStatusOrderByUpdatedAtDesc(
                tenant.orgId(), tenant.userId(), scopeType, scopeId.trim(), ContextFactStatus.ACTIVE);
    }

    /**
     * 仅返回可以直接作为当前上下文依据的事实；Agent 推断、过期和待刷新事实不会静默放行。
     */
    @Transactional(readOnly = true)
    public List<ContextFactRecord> resolveCurrent(ContextFactScopeType scopeType, String scopeId) {
        return listActive(scopeType, scopeId).stream()
                .filter(this::isCurrent)
                .sorted(Comparator.comparing(ContextFactRecord::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * 为聊天上下文提供最小事实片段。valueJson/evidence 不进入模型 prompt，避免把事实索引
     * 退化成原文存储；调用方还会按 token 预算裁剪整个事实段。
     */
    @Transactional(readOnly = true)
    public List<ContextFactSnippet> resolveForChat(String sessionId) {
        String normalized = sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
        return resolveCurrent(ContextFactScopeType.CHAT_SESSION, normalized).stream()
                .limit(24)
                .map(record -> new ContextFactSnippet(record.getFactKey(), record.getFactType().name(),
                        record.getDisplaySummary(), record.getTrustLevel().name(), record.getSourceType().name(),
                        record.getSourceId()))
                .toList();
    }

    /**
     * 来源失效不删除历史，也不伪造新的事实版本；后续上下文解析会跳过 NEEDS_REFRESH。
     */
    @Transactional
    public int markNeedsRefreshBySource(ContextFactSourceType sourceType, String sourceId, String reason) {
        Tenant tenant = tenant(RequestIdentityContext.require());
        if (sourceType == null) {
            throw new IllegalArgumentException("操作失败：事实来源类型不能为空。");
        }
        String normalizedSourceId = required(sourceId, "sourceId", 160);
        String normalizedReason = normalizeReason(reason == null ? "来源已变化，需要重新确认" : reason);
        List<ContextFactRecord> records = repository.findByOrgIdAndUserIdAndSourceTypeAndSourceIdAndStatus(
                tenant.orgId(), tenant.userId(), sourceType, normalizedSourceId, ContextFactStatus.ACTIVE);
        records.forEach(record -> record.markNeedsRefresh(normalizedReason));
        repository.saveAll(records);
        return records.size();
    }

    @Transactional(readOnly = true)
    public List<ContextFactRecord> listActiveForSubject(String subjectType, String subjectId) {
        Tenant tenant = tenant(RequestIdentityContext.require());
        validateShortId(subjectType, "subjectType", 64);
        validateShortId(subjectId, "subjectId", 128);
        return repository.findByOrgIdAndUserIdAndSubjectTypeAndSubjectIdAndStatusOrderByUpdatedAtDesc(
                tenant.orgId(), tenant.userId(), subjectType.trim(), subjectId.trim(), ContextFactStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public ContextFactRecord get(String id) {
        Tenant tenant = tenant(RequestIdentityContext.require());
        validateShortId(id, "事实 ID", 36);
        return repository.findByIdAndOrgIdAndUserId(id.trim(), tenant.orgId(), tenant.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：事实不存在或无权访问。"));
    }

    /** 撤销只影响当前租户指定版本，不删除历史事实。 */
    @Transactional
    public ContextFactRecord revoke(String id, String reason) {
        Tenant tenant = tenant(RequestIdentityContext.require());
        validateShortId(id, "事实 ID", 36);
        ContextFactRecord record = repository.findByIdAndOrgIdAndUserId(id.trim(), tenant.orgId(), tenant.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：事实不存在或无权撤销。"));
        if (record.getStatus() != ContextFactStatus.REVOKED) {
            record.revoke(normalizeReason(reason));
            repository.save(record);
        }
        return record;
    }

    /**
     * 迭代 34：用户确认事实——以 {@code USER_CONFIRMED + CURRENT} 重新捕获同一 (scope, factKey)，
     * 复用 capture 的 supersede/revision 链完成替代与审计；确认时可修正 valueJson/displaySummary。
     */
    @Transactional
    public ContextFactRecord confirm(String id, ConfirmRequest request) {
        return confirm(RequestIdentityContext.require(), id, request);
    }

    /** 异步/测试持有身份快照时的确认入口；租户校验与 capture 一致。 */
    @Transactional
    public ContextFactRecord confirm(RequestIdentity identity, String id, ConfirmRequest request) {
        Tenant tenant = tenant(identity);
        validateShortId(id, "事实 ID", 36);
        ContextFactRecord record = repository.findByIdAndOrgIdAndUserId(id.trim(), tenant.orgId(), tenant.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：事实不存在或无权访问。"));
        String valueJson = request == null ? null : optionalJson(request.valueJson());
        String displaySummary = request == null || request.displaySummary() == null
                || request.displaySummary().isBlank() ? record.getDisplaySummary() : request.displaySummary();
        return capture(identity, new CaptureRequest(record.getScopeType(), record.getScopeId(),
                record.getSubjectType(), record.getSubjectId(), record.getFactKey(), record.getFactType(),
                valueJson == null ? record.getValueJson() : valueJson, displaySummary,
                ContextFactTrustLevel.USER_CONFIRMED, ContextFactVerificationState.CURRENT,
                record.getSourceType(), record.getSourceId(), record.getSourceVersion(),
                record.getSourceFingerprint(), record.getEvidenceRefsJson(),
                record.getObservedAt(), Instant.now(), record.getValidUntil()));
    }

    private static String optionalJson(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** 删除会话/任务时清理该范围事实；调用方已通过租户身份校验。 */
    @Transactional
    public long deleteScope(ContextFactScopeType scopeType, String scopeId) {
        Tenant tenant = tenant(RequestIdentityContext.require());
        validateScope(scopeType, scopeId);
        return repository.deleteByOrgIdAndUserIdAndScopeTypeAndScopeId(
                tenant.orgId(), tenant.userId(), scopeType, scopeId.trim());
    }

    private boolean isCurrent(ContextFactRecord record) {
        if (record.getVerificationState() != ContextFactVerificationState.CURRENT) {
            return false;
        }
        return record.getValidUntil() == null || record.getValidUntil().isAfter(Instant.now());
    }

    private static boolean equivalent(ContextFactRecord current, Normalized next) {
        return Objects.equals(current.getFactType(), next.factType())
                && Objects.equals(current.getValueHash(), next.valueHash())
                && Objects.equals(current.getDisplaySummary(), next.displaySummary())
                && Objects.equals(current.getTrustLevel(), next.trustLevel())
                && Objects.equals(current.getVerificationState(), next.verificationState())
                && Objects.equals(current.getSourceType(), next.sourceType())
                && Objects.equals(current.getSourceId(), next.sourceId())
                && Objects.equals(current.getSourceVersion(), next.sourceVersion())
                && Objects.equals(current.getSourceFingerprint(), next.sourceFingerprint())
                && Objects.equals(current.getEvidenceRefsJson(), next.evidenceRefsJson())
                && Objects.equals(current.getValidUntil(), next.validUntil());
    }

    private Normalized normalize(CaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("操作失败：关键事实不能为空。");
        }
        validateScope(request.scopeType(), request.scopeId());
        String subjectType = optionalId(request.subjectType(), "subjectType", 64);
        String subjectId = optionalId(request.subjectId(), "subjectId", 128);
        if ((subjectType == null) != (subjectId == null)) {
            throw new IllegalArgumentException("操作失败：subjectType 和 subjectId 必须同时提供。");
        }
        String factKey = required(request.factKey(), "factKey", 160);
        if (!FACT_KEY.matcher(factKey).matches()) {
            throw new IllegalArgumentException("操作失败：factKey 只能包含字母、数字、点、冒号、下划线和连字符。");
        }
        if (request.factType() == null) {
            throw new IllegalArgumentException("操作失败：事实类型不能为空。");
        }
        if (request.sourceType() == null) {
            throw new IllegalArgumentException("操作失败：事实来源类型不能为空。");
        }
        String sourceId = required(request.sourceId(), "sourceId", 160);
        String displaySummary = required(request.displaySummary(), "displaySummary", MAX_DISPLAY_SUMMARY_CHARS);
        String valueJson = normalizeValueJson(request.valueJson());
        String evidenceRefsJson = normalizeEvidence(request.evidenceRefsJson());
        ContextFactTrustLevel trust = request.trustLevel() == null
                ? ContextFactTrustLevel.SYSTEM_VERIFIED : request.trustLevel();
        ContextFactVerificationState verification = request.verificationState() == null
                ? defaultVerification(trust) : request.verificationState();
        if (trust == ContextFactTrustLevel.AGENT_DERIVED
                && verification == ContextFactVerificationState.CURRENT) {
            verification = ContextFactVerificationState.NEEDS_REFRESH;
        }
        if (verification == ContextFactVerificationState.REVOKED) {
            throw new IllegalArgumentException("操作失败：新事实不能以 REVOKED 状态写入。");
        }
        Instant observedAt = request.observedAt() == null ? Instant.now() : request.observedAt();
        Instant verifiedAt = request.verifiedAt();
        if (verification == ContextFactVerificationState.CURRENT && verifiedAt == null) {
            verifiedAt = observedAt;
        }
        if (request.validUntil() != null && request.validUntil().isBefore(observedAt)) {
            throw new IllegalArgumentException("操作失败：事实有效期不能早于观测时间。");
        }
        String sourceVersion = optionalId(request.sourceVersion(), "sourceVersion", 128);
        String sourceFingerprint = optionalId(request.sourceFingerprint(), "sourceFingerprint", 128);
        return new Normalized(request.scopeType(), request.scopeId().trim(), subjectType, subjectId, factKey,
                request.factType(), valueJson, displaySummary, sha256(valueJson), trust, verification,
                request.sourceType(), sourceId, sourceVersion, sourceFingerprint, evidenceRefsJson, observedAt,
                verifiedAt, request.validUntil());
    }

    private String normalizeValueJson(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank() || value.length() > MAX_VALUE_JSON_CHARS) {
            throw new IllegalArgumentException("操作失败：关键事实 valueJson 不能为空且不能超过 "
                    + MAX_VALUE_JSON_CHARS + " 个字符。");
        }
        try {
            JsonNode node = mapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("操作失败：关键事实 valueJson 必须是 JSON 对象。");
            }
            validateTree(node, 0, false);
            String canonical = canonical(node).toString();
            if (canonical.length() > MAX_VALUE_JSON_CHARS) {
                throw new IllegalArgumentException("操作失败：关键事实 valueJson 规范化后超过大小上限。");
            }
            return canonical;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("操作失败：关键事实 valueJson 不是合法 JSON。", e);
        }
    }

    private String normalizeEvidence(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > MAX_EVIDENCE_JSON_CHARS) {
            throw new IllegalArgumentException("操作失败：证据引用不能超过 " + MAX_EVIDENCE_JSON_CHARS + " 个字符。");
        }
        try {
            JsonNode node = mapper.readTree(raw.trim());
            if (node == null || !node.isArray()) {
                throw new IllegalArgumentException("操作失败：证据引用必须是 JSON 数组。");
            }
            validateTree(node, 0, true);
            String canonical = canonical(node).toString();
            if (canonical.length() > MAX_EVIDENCE_JSON_CHARS) {
                throw new IllegalArgumentException("操作失败：证据引用规范化后超过大小上限。");
            }
            return canonical;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("操作失败：证据引用不是合法 JSON。", e);
        }
    }

    private void validateTree(JsonNode node, int depth, boolean evidence) {
        if (depth > MAX_JSON_DEPTH) {
            throw new IllegalArgumentException("操作失败：关键事实 JSON 嵌套层级过深。");
        }
        if (node.isObject()) {
            if (node.size() > MAX_OBJECT_FIELDS) {
                throw new IllegalArgumentException("操作失败：关键事实 JSON 字段过多。");
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().toLowerCase(Locale.ROOT).replace("-", "_");
                if (FORBIDDEN_KEYS.contains(normalized)) {
                    throw new IllegalArgumentException("操作失败：关键事实不能保存敏感或原始内容字段。");
                }
                validateTree(field.getValue(), depth + 1, evidence);
            }
            return;
        }
        if (node.isArray()) {
            if (node.size() > MAX_ARRAY_ITEMS) {
                throw new IllegalArgumentException("操作失败：关键事实 JSON 数组项目过多。");
            }
            for (JsonNode child : node) {
                validateTree(child, depth + 1, evidence);
            }
            return;
        }
        if (node.isTextual() && node.textValue().length() > MAX_STRING_CHARS) {
            throw new IllegalArgumentException("操作失败：关键事实 JSON 文本字段过长。");
        }
        if (!node.isValueNode()) {
            throw new IllegalArgumentException("操作失败：关键事实 JSON 包含不支持的节点。");
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            sorted.forEach(object::set);
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return node;
    }

    private static ContextFactVerificationState defaultVerification(ContextFactTrustLevel trust) {
        return trust == ContextFactTrustLevel.AGENT_DERIVED
                ? ContextFactVerificationState.NEEDS_REFRESH : ContextFactVerificationState.CURRENT;
    }

    private static String normalizeReason(String reason) {
        String value = reason == null || reason.isBlank() ? "用户撤销" : reason.trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String required(String value, String name, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("操作失败：" + name + "不能为空且不能超过 " + max + " 个字符。");
        }
        return normalized;
    }

    private static String optionalId(String value, String name, int max) {
        if (value == null || value.isBlank()) return null;
        return required(value, name, max);
    }

    private static void validateScope(ContextFactScopeType scopeType, String scopeId) {
        if (scopeType == null) {
            throw new IllegalArgumentException("操作失败：事实范围类型不能为空。");
        }
        required(scopeId, "scopeId", 128);
    }

    private static void validateShortId(String value, String name, int max) {
        required(value, name, max);
    }

    private static Tenant tenant(RequestIdentity identity) {
        if (identity == null || identity.userId() == null || identity.orgId() == null) {
            throw new IllegalStateException("操作失败：当前执行链路缺少用户身份，请重新登录后重试。");
        }
        return new Tenant(identity, identity.orgId(), identity.userId());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：无法计算关键事实指纹。", e);
        }
    }

    public record CaptureRequest(ContextFactScopeType scopeType, String scopeId, String subjectType,
            String subjectId, String factKey, ContextFactType factType, String valueJson, String displaySummary,
            ContextFactTrustLevel trustLevel, ContextFactVerificationState verificationState,
            ContextFactSourceType sourceType, String sourceId, String sourceVersion, String sourceFingerprint,
            String evidenceRefsJson, Instant observedAt, Instant verifiedAt, Instant validUntil) {
    }

    /** 迭代 34：确认时可修正事实值与摘要；其余字段继承原记录，不允许伪造来源与观测时间。 */
    public record ConfirmRequest(String valueJson, String displaySummary) {
    }

    /** 只供 prompt 组装的短事实，不是对外 API 视图。 */
    public record ContextFactSnippet(String factKey, String factType, String displaySummary,
            String trustLevel, String sourceType, String sourceId) {
    }

    private record Tenant(RequestIdentity identity, Long orgId, Long userId) { }

    private record Normalized(ContextFactScopeType scopeType, String scopeId, String subjectType, String subjectId,
            String factKey, ContextFactType factType, String valueJson, String displaySummary, String valueHash,
            ContextFactTrustLevel trustLevel, ContextFactVerificationState verificationState,
            ContextFactSourceType sourceType, String sourceId, String sourceVersion, String sourceFingerprint,
            String evidenceRefsJson, Instant observedAt, Instant verifiedAt, Instant validUntil) { }
}
