package com.example.vatica.action;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 动作记录查询始终带 userId，幂等键仅在同一用户范围内唯一。 */
public interface ActionExecutionRecordRepository extends JpaRepository<ActionExecutionRecord, String> {

    List<ActionExecutionRecord> findByUserIdAndSubjectTypeAndSubjectId(Long userId, String subjectType, String subjectId);

    /** 迭代 33：按当前用户/组织和业务范围读取有限的审批执行事实。 */
    List<ActionExecutionRecord> findByUserIdAndOrgIdAndSubjectIdOrderByUpdatedAtDesc(Long userId, Long orgId,
            String subjectId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActionExecutionRecord a where a.userId = :userId and a.idempotencyKey = :idempotencyKey")
    Optional<ActionExecutionRecord> findForUpdate(@Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);
}
