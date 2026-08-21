package com.example.vatica.report;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyReportDraftRepository extends JpaRepository<WeeklyReportDraftRecord, String> {
    Optional<WeeklyReportDraftRecord> findByIdAndUserId(String id, Long userId);

    List<WeeklyReportDraftRecord> findTop20ByUserIdOrderByUpdatedAtDesc(Long userId);
}
