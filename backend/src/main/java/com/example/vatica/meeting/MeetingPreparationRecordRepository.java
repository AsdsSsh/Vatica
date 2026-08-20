package com.example.vatica.meeting;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** 会议准备查询始终带 userId，延续日历、待办和任务的租户隔离语义。 */
public interface MeetingPreparationRecordRepository extends JpaRepository<MeetingPreparationRecord, String> {
    Optional<MeetingPreparationRecord> findByIdAndUserId(String id, Long userId);

    List<MeetingPreparationRecord> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}
