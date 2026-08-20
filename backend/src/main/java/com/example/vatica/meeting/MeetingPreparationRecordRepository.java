package com.example.vatica.meeting;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 会议准备查询始终带 userId，延续日历、待办和任务的租户隔离语义。 */
public interface MeetingPreparationRecordRepository extends JpaRepository<MeetingPreparationRecord, String> {
    Optional<MeetingPreparationRecord> findByIdAndUserId(String id, Long userId);

    /** 批准写入时串行化同一草案，确保重复点击不会重复创建待办。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MeetingPreparationRecord p where p.id = :id and p.userId = :userId")
    Optional<MeetingPreparationRecord> findForApproval(@Param("id") String id, @Param("userId") Long userId);

    List<MeetingPreparationRecord> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}
