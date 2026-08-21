package com.example.vatica.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface CalendarEventRecordRepository extends JpaRepository<CalendarEventRecord, Long> {
    List<CalendarEventRecord> findByUserIdOrderByStartAtAsc(Long userId);

    /** 26A：周报事实收集必须同时按组织和用户收口。 */
    List<CalendarEventRecord> findByOrgIdAndUserIdOrderByStartAtAsc(Long orgId, Long userId);

    Optional<CalendarEventRecord> findByIdAndUserId(Long id, Long userId);

    @Transactional
    void deleteByUserId(Long userId);
}
