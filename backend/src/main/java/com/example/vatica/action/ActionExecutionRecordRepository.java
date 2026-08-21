package com.example.vatica.action;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 动作记录查询始终带 userId，幂等键仅在同一用户范围内唯一。 */
public interface ActionExecutionRecordRepository extends JpaRepository<ActionExecutionRecord, String> {

    List<ActionExecutionRecord> findByUserIdAndSubjectTypeAndSubjectId(Long userId, String subjectType, String subjectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ActionExecutionRecord a where a.userId = :userId and a.idempotencyKey = :idempotencyKey")
    Optional<ActionExecutionRecord> findForUpdate(@Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);
}
