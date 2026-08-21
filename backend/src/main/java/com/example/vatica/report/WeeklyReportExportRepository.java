package com.example.vatica.report;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyReportExportRepository extends JpaRepository<WeeklyReportExportRecord, String> {
    Optional<WeeklyReportExportRecord> findByIdAndUserId(String id, Long userId);

    Optional<WeeklyReportExportRecord> findByDraftIdAndUserId(String draftId, Long userId);

    List<WeeklyReportExportRecord> findTop20ByUserIdOrderByUpdatedAtDesc(Long userId);
}
