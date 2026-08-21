package com.example.vatica.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.artifact.ArtifactService;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.fasterxml.jackson.databind.ObjectMapper;

class WeeklyReportDraftServiceTest {

    private final WeeklyReportService facts = mock(WeeklyReportService.class);
    private final WeeklyReportDraftRepository repository = mock(WeeklyReportDraftRepository.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WeeklyReportDraftService service = new WeeklyReportDraftService(facts, repository, artifacts, mapper);

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void createsEditableDraftFromFrozenFactsAndIndexesSelectedPreviews() {
        RequestIdentity identity = new RequestIdentity(7L, 11L, "USER", "alice");
        RequestIdentityContext.set(identity);
        when(facts.collect(any())).thenReturn(snapshot());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifacts.listForSubject(eq(identity), eq(WeeklyReportDraftService.SUBJECT_TYPE), any()))
                .thenReturn(List.of());

        WeeklyReportDraftService.WeeklyReportDraftView result = service.create(
                new WeeklyReportDraftService.CreateRequest(LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30),
                        "WEEKLY", true, true, false, "关注上线", "", "", "", "下周发布", true, true));

        assertThat(result.title()).isEqualTo("2026-08-24 至 2026-08-30 周报");
        assertThat(result.focus()).contains("完成 1 项待办").contains("参加 1 场会议");
        assertThat(result.risks()).contains("1 项待办").contains("逾期");
        assertThat(result.wordPreview()).contains("# 2026-08-24 至 2026-08-30 周报", "日程事实", "项目周会");
        assertThat(result.excelPreview()).contains("会议数量,1", "已完成待办,1", "完成登录,已完成");
        verify(artifacts).syncWeeklyReportDraft(identity, result.id(), true, true,
                result.wordPreview(), result.excelPreview());
    }

    @Test
    void editingDraftUsesStoredSnapshotWithoutCollectingFactsAgain() throws Exception {
        RequestIdentity identity = new RequestIdentity(7L, 11L, "USER", "alice");
        RequestIdentityContext.set(identity);
        WeeklyReportDraftRecord record = new WeeklyReportDraftRecord("draft-1", identity, "旧标题", "旧重点", "",
                "", true, true, mapper.writeValueAsString(snapshot()));
        when(repository.findByIdAndUserId("draft-1", 7L)).thenReturn(java.util.Optional.of(record));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifacts.listForSubject(identity, WeeklyReportDraftService.SUBJECT_TYPE, "draft-1"))
                .thenReturn(List.of());

        WeeklyReportDraftService.WeeklyReportDraftView result = service.update("draft-1",
                new WeeklyReportDraftService.UpdateRequest("更新后的周报", "明确重点", "暂无阻塞", "继续联调", true, false));

        assertThat(result.title()).isEqualTo("更新后的周报");
        assertThat(result.focus()).isEqualTo("明确重点");
        assertThat(result.wordPreview()).contains("明确重点", "暂无阻塞", "继续联调");
        assertThat(result.excelPreview()).isNull();
        verify(facts, never()).collect(any());
        verify(artifacts).syncWeeklyReportDraft(identity, "draft-1", true, false, result.wordPreview(), null);
    }

    private static WeeklyReportService.WeeklyReportView snapshot() {
        return new WeeklyReportService.WeeklyReportView("weekly:key", "WEEKLY", LocalDate.of(2026, 8, 24),
                LocalDate.of(2026, 8, 30), List.of(),
                List.of(new WeeklyReportService.CalendarFact(1L, "项目周会", "2026-08-24 10:00",
                        "2026-08-24 11:00", false)),
                List.of(new WeeklyReportService.TodoFact("todo-1", "完成登录", "2026-08-25", true,
                                "2026-08-20 10:00"),
                        new WeeklyReportService.TodoFact("todo-2", "处理风险", "2026-08-26", false,
                                "2026-08-20 11:00")),
                new WeeklyReportService.Statistics(1, 2, 1, 1, 1), "关注上线", List.of(),
                "2026-08-21T00:00:00Z");
    }
}
