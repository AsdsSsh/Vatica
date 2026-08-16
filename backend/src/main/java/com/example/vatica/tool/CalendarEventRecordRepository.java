package com.example.vatica.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface CalendarEventRecordRepository extends JpaRepository<CalendarEventRecord, Long> {
    List<CalendarEventRecord> findByUserIdOrderByStartAtAsc(Long userId);

    Optional<CalendarEventRecord> findByIdAndUserId(Long id, Long userId);

    @Transactional
    void deleteByUserId(Long userId);
}
