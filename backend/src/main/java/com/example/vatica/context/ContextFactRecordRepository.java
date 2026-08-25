package com.example.vatica.context;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

/** 迭代 29B：事实查询必须同时带组织和用户，禁止跨租户按事实 ID读取。 */
public interface ContextFactRecordRepository extends JpaRepository<ContextFactRecord, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContextFactRecord> findTopByOrgIdAndUserIdAndScopeTypeAndScopeIdAndFactKeyOrderByRevisionDesc(
            Long orgId, Long userId, ContextFactScopeType scopeType, String scopeId, String factKey);

    Optional<ContextFactRecord> findByIdAndOrgIdAndUserId(String id, Long orgId, Long userId);

    List<ContextFactRecord> findByOrgIdAndUserIdAndScopeTypeAndScopeIdAndStatusOrderByUpdatedAtDesc(
            Long orgId, Long userId, ContextFactScopeType scopeType, String scopeId, ContextFactStatus status);

    List<ContextFactRecord> findByOrgIdAndUserIdAndSubjectTypeAndSubjectIdAndStatusOrderByUpdatedAtDesc(
            Long orgId, Long userId, String subjectType, String subjectId, ContextFactStatus status);

    List<ContextFactRecord> findByOrgIdAndUserIdAndSourceTypeAndSourceIdAndStatus(
            Long orgId, Long userId, ContextFactSourceType sourceType, String sourceId, ContextFactStatus status);

    long deleteByOrgIdAndUserIdAndScopeTypeAndScopeId(Long orgId, Long userId, ContextFactScopeType scopeType,
            String scopeId);
}
