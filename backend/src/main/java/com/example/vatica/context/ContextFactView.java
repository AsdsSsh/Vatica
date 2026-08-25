package com.example.vatica.context;

import java.time.Instant;

/**
 * 迭代 29B：事实对外视图。
 *
 * <p>故意不包含 valueJson、valueHash、evidenceRefsJson、组织/用户 ID，避免把
 * 受控索引或内部租户信息直接暴露给桌面端。</p>
 */
public record ContextFactView(String id, ContextFactScopeType scopeType, String scopeId,
        String subjectType, String subjectId, String factKey, int revision, ContextFactType factType,
        String displaySummary, ContextFactStatus status, ContextFactTrustLevel trustLevel,
        ContextFactVerificationState verificationState, ContextFactSourceType sourceType, String sourceId,
        String sourceVersion, String sourceFingerprint, Instant observedAt, Instant verifiedAt,
        Instant validUntil, String statusReason, Instant revokedAt, Instant createdAt, Instant updatedAt) {

    static ContextFactView from(ContextFactRecord record) {
        return new ContextFactView(record.getId(), record.getScopeType(), record.getScopeId(),
                record.getSubjectType(), record.getSubjectId(), record.getFactKey(), record.getRevision(),
                record.getFactType(), record.getDisplaySummary(), record.getStatus(), record.getTrustLevel(),
                record.getVerificationState(), record.getSourceType(), record.getSourceId(),
                record.getSourceVersion(), record.getSourceFingerprint(), record.getObservedAt(),
                record.getVerifiedAt(), record.getValidUntil(), record.getStatusReason(), record.getRevokedAt(),
                record.getCreatedAt(), record.getUpdatedAt());
    }
}
