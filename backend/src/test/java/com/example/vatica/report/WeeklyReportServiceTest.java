package com.example.vatica.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.knowledge.KnowledgeBaseService;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.IcsParser.CalendarEvent;
import com.example.vatica.tool.TodoRecord;
import com.example.vatica.tool.TodoRecordRepository;
import com.example.vatica.tool.TodoTools;

class WeeklyReportServiceTest {

    private final CalendarEventRecordRepository events = mock(CalendarEventRecordRepository.class);
    private final TodoRecordRepository todos = mock(TodoRecordRepository.class);
    private final KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
    private final WeeklyReportService service = new WeeklyReportService(events, todos, knowledge);

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void collectsOrgAndUserScopedCalendarOccurrencesAndTodoStatistics() {
        RequestIdentityContext.set(new RequestIdentity(7L, 11L, "USER", "alice"));
        when(events.findByOrgIdAndUserIdOrderByStartAtAsc(11L, 7L)).thenReturn(List.of(
                new CalendarEventRecord(7L, 11L, new CalendarEvent("周会", LocalDateTime.of(2026, 8, 24, 10, 0),
                        LocalDateTime.of(2026, 8, 24, 11, 0), com.example.vatica.tool.IcsParser.parseRrule("FREQ=WEEKLY;COUNT=3")))));
        when(todos.findByOrgIdAndUserId(11L, 7L)).thenReturn(List.of(
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-1", "完成周报", "2026-08-25", true, "2026-08-20 10:00")),
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-2", "确认风险", "2026-08-27", false, "2026-08-20 11:00")),
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-3", "无日期事项", null, false, "2026-08-20 12:00"))));

        WeeklyReportService.WeeklyReportView result = service.collect(new WeeklyReportService.WeeklyReportRequest(
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), "WEEKLY", true, true, false,
                "本周重点是上线风险"));

        assertThat(result.calendar()).hasSize(1);
        assertThat(result.calendar().get(0).title()).isEqualTo("周会");
        assertThat(result.todos()).extracting(WeeklyReportService.TodoFact::todoId)
                .containsExactly("todo-1", "todo-2");
        assertThat(result.statistics()).isEqualTo(new WeeklyReportService.Statistics(1, 2, 1, 1, 1));
        assertThat(result.userNotes()).isEqualTo("本周重点是上线风险");
        assertThat(result.warnings()).anyMatch(value -> value.contains("无截止日期"));
        verify(events).findByOrgIdAndUserIdOrderByStartAtAsc(11L, 7L);
        verify(todos).findByOrgIdAndUserId(11L, 7L);
    }

    @Test
    void reportsKnowledgeAsDegradedWithoutBlockingCalendarAndTodoFacts() {
        RequestIdentityContext.set(new RequestIdentity(7L, 11L, "USER", "alice"));
        when(events.findByOrgIdAndUserIdOrderByStartAtAsc(11L, 7L)).thenReturn(List.of());
        when(todos.findByOrgIdAndUserId(11L, 7L)).thenReturn(List.of());
        when(knowledge.listDocuments()).thenThrow(new IllegalStateException("知识库未启用"));

        WeeklyReportService.WeeklyReportView result = service.collect(new WeeklyReportService.WeeklyReportRequest(
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), "WEEKLY", true, true, true, null));

        assertThat(result.sources()).filteredOn(source -> source.source().equals("KNOWLEDGE"))
                .singleElement().extracting(WeeklyReportService.SourceView::status).isEqualTo("DEGRADED");
        assertThat(result.warnings()).anyMatch(value -> value.contains("知识库当前不可用"));
    }

    @Test
    void validatesRangeAndReportTypeBeforeReadingData() {
        RequestIdentityContext.set(new RequestIdentity(7L, 11L, "USER", "alice"));
        WeeklyReportService.WeeklyReportRequest tooLong = new WeeklyReportService.WeeklyReportRequest(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), "WEEKLY", true, true, false, null);
        assertThatThrownBy(() -> service.collect(tooLong)).hasMessageContaining("不能超过 31 天");
        WeeklyReportService.WeeklyReportRequest wrongType = new WeeklyReportService.WeeklyReportRequest(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), "MONTHLY", true, true, false, null);
        assertThatThrownBy(() -> service.collect(wrongType)).hasMessageContaining("只支持 WEEKLY 或 WORK_WEEK");
    }
}
