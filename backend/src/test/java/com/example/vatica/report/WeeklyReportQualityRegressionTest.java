package com.example.vatica.report;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.example.vatica.knowledge.KnowledgeDocumentStatus;
import com.example.vatica.knowledge.KnowledgeVisibility;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.IcsParser.CalendarEvent;
import com.example.vatica.tool.TodoRecord;
import com.example.vatica.tool.TodoRecordRepository;
import com.example.vatica.tool.TodoTools;

/**
 * 迭代 26D：固定样例质量回归。
 *
 * <p>样例刻意覆盖范围首尾、周末、无截止日期和非法日期，验证统计口径与租户查询边界。
 * 知识库只验证就绪度提示，不把“有文档”伪装成已经产生引用。</p>
 */
class WeeklyReportQualityRegressionTest {

    private final CalendarEventRecordRepository events = mock(CalendarEventRecordRepository.class);
    private final TodoRecordRepository todos = mock(TodoRecordRepository.class);
    private final KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
    private final WeeklyReportService service = new WeeklyReportService(events, todos, knowledge);

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void fixedWeeklyFixtureKeepsInclusiveBoundariesAndStableStatistics() {
        RequestIdentity alice = new RequestIdentity(7L, 11L, "USER", "alice");
        RequestIdentityContext.set(alice);
        stubFixture(alice, fixtureEvents(), fixtureTodos());

        WeeklyReportService.WeeklyReportView result = service.collect(request("WEEKLY", false));

        assertThat(result.calendar()).extracting(WeeklyReportService.CalendarFact::title)
                .containsExactly("范围首日会议", "范围末日会议");
        assertThat(result.todos()).extracting(WeeklyReportService.TodoFact::todoId)
                .containsExactly("todo-start", "todo-overdue", "todo-end");
        assertThat(result.statistics()).isEqualTo(new WeeklyReportService.Statistics(2, 3, 1, 2, 1));
        assertThat(source(result, "CALENDAR").recordCount()).isEqualTo(2);
        assertThat(source(result, "TODO").recordCount()).isEqualTo(3);
        assertThat(result.warnings()).anyMatch(value -> value.contains("无截止日期"));
        assertThat(result.warnings()).anyMatch(value -> value.contains("无法解析"));
    }

    @Test
    void workWeekFixtureExcludesWeekendButKeepsWeekdayBoundaries() {
        RequestIdentityContext.set(new RequestIdentity(7L, 11L, "USER", "alice"));
        stubFixture(new RequestIdentity(7L, 11L, "USER", "alice"), fixtureEvents(), fixtureTodos());

        WeeklyReportService.WeeklyReportView result = service.collect(request("WORK_WEEK", false));

        assertThat(result.calendar()).extracting(WeeklyReportService.CalendarFact::title)
                .containsExactly("范围首日会议");
        assertThat(result.todos()).extracting(WeeklyReportService.TodoFact::todoId)
                .containsExactly("todo-start", "todo-overdue");
        assertThat(result.statistics()).isEqualTo(new WeeklyReportService.Statistics(1, 2, 1, 1, 1));
    }

    @Test
    void knowledgeReadinessIsShownWithoutInventingCitations() {
        RequestIdentity identity = new RequestIdentity(7L, 11L, "USER", "alice");
        RequestIdentityContext.set(identity);
        stubFixture(identity, List.of(), List.of());
        when(knowledge.listDocuments()).thenReturn(List.of(new KnowledgeBaseService.DocumentView(42L,
                "项目资料.md", "C:/workspace/项目资料.md", KnowledgeVisibility.PRIVATE, "hash", 1,
                KnowledgeDocumentStatus.READY, 3, null, "2026-08-21T00:00:00Z")));

        WeeklyReportService.WeeklyReportView result = service.collect(request("WEEKLY", true));

        assertThat(source(result, "KNOWLEDGE").status()).isEqualTo("READY");
        assertThat(source(result, "KNOWLEDGE").recordCount()).isEqualTo(1);
        assertThat(result.warnings()).anyMatch(value -> value.contains("未执行语义检索"));
    }

    @Test
    void identicalFixtureRemainsTenantScoped() {
        RequestIdentity alice = new RequestIdentity(7L, 11L, "USER", "alice");
        RequestIdentity bob = new RequestIdentity(8L, 11L, "USER", "bob");
        when(events.findByOrgIdAndUserIdOrderByStartAtAsc(11L, 7L)).thenReturn(fixtureEvents());
        when(events.findByOrgIdAndUserIdOrderByStartAtAsc(11L, 8L)).thenReturn(List.of(
                new CalendarEventRecord(8L, 11L, new CalendarEvent("Bob 私有会议",
                        LocalDateTime.of(2026, 8, 18, 9, 0), LocalDateTime.of(2026, 8, 18, 10, 0), null))));
        when(todos.findByOrgIdAndUserId(11L, 7L)).thenReturn(fixtureTodos());
        when(todos.findByOrgIdAndUserId(11L, 8L)).thenReturn(List.of(
                new TodoRecord(8L, 11L, new TodoTools.Todo("bob-todo", "Bob 私有待办", "2026-08-18", false,
                        "2026-08-17 09:00"))));

        RequestIdentityContext.set(alice);
        WeeklyReportService.WeeklyReportView aliceResult = service.collect(request("WEEKLY", false));
        RequestIdentityContext.set(bob);
        WeeklyReportService.WeeklyReportView bobResult = service.collect(request("WEEKLY", false));

        assertThat(aliceResult.calendar()).extracting(WeeklyReportService.CalendarFact::title)
                .containsExactly("范围首日会议", "范围末日会议");
        assertThat(bobResult.calendar()).extracting(WeeklyReportService.CalendarFact::title)
                .containsExactly("Bob 私有会议");
        assertThat(bobResult.todos()).extracting(WeeklyReportService.TodoFact::todoId)
                .containsExactly("bob-todo");
        verify(events).findByOrgIdAndUserIdOrderByStartAtAsc(11L, 7L);
        verify(events).findByOrgIdAndUserIdOrderByStartAtAsc(11L, 8L);
        verify(todos).findByOrgIdAndUserId(11L, 7L);
        verify(todos).findByOrgIdAndUserId(11L, 8L);
    }

    private void stubFixture(RequestIdentity identity, List<CalendarEventRecord> eventRows,
            List<TodoRecord> todoRows) {
        when(events.findByOrgIdAndUserIdOrderByStartAtAsc(identity.orgId(), identity.userId())).thenReturn(eventRows);
        when(todos.findByOrgIdAndUserId(identity.orgId(), identity.userId())).thenReturn(todoRows);
    }

    private static WeeklyReportService.WeeklyReportRequest request(String type, boolean includeKnowledge) {
        return new WeeklyReportService.WeeklyReportRequest(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23),
                type, true, true, includeKnowledge, "本周固定样例");
    }

    private static WeeklyReportService.SourceView source(WeeklyReportService.WeeklyReportView view, String name) {
        return view.sources().stream().filter(value -> value.source().equals(name)).findFirst().orElseThrow();
    }

    private static List<CalendarEventRecord> fixtureEvents() {
        return List.of(
                new CalendarEventRecord(7L, 11L, new CalendarEvent("范围首日会议",
                        LocalDateTime.of(2026, 8, 17, 9, 0), LocalDateTime.of(2026, 8, 17, 10, 0), null)),
                new CalendarEventRecord(7L, 11L, new CalendarEvent("范围末日会议",
                        LocalDateTime.of(2026, 8, 23, 16, 0), LocalDateTime.of(2026, 8, 23, 17, 0), null)),
                new CalendarEventRecord(7L, 11L, new CalendarEvent("范围外会议",
                        LocalDateTime.of(2026, 8, 24, 9, 0), LocalDateTime.of(2026, 8, 24, 10, 0), null)));
    }

    private static List<TodoRecord> fixtureTodos() {
        return List.of(
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-start", "范围首日待办", "2026-08-17", true,
                        "2026-08-16 09:00")),
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-overdue", "范围内逾期待办", "2026-08-18", false,
                        "2026-08-17 09:00")),
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-end", "范围末日待办", "2026-08-23", false,
                        "2026-08-22 09:00")),
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-no-due", "无日期待办", null, false,
                        "2026-08-17 10:00")),
                new TodoRecord(7L, 11L, new TodoTools.Todo("todo-invalid", "非法日期待办", "not-a-date", false,
                        "2026-08-17 11:00")));
    }
}
