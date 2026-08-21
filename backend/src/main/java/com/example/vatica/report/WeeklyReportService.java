package com.example.vatica.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.knowledge.KnowledgeBaseService;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.IcsParser;
import com.example.vatica.tool.TodoRecord;
import com.example.vatica.tool.TodoRecordRepository;
import com.example.vatica.tool.TodoTools;

/**
 * 迭代 26A：周报事实收集器。
 *
 * <p>本阶段只收集确定性事实，不调用模型、不写文件、不写待办、不发邮件。日期范围、数据源
 * 和统计口径在服务端固定，后续 26B 的草案生成只能消费这个事实快照，不能自行重新查询。</p>
 */
@Service
public class WeeklyReportService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_RANGE_DAYS = 31;

    private final CalendarEventRecordRepository eventRepository;
    private final TodoRecordRepository todoRepository;
    private final KnowledgeBaseService knowledge;

    public WeeklyReportService(CalendarEventRecordRepository eventRepository,
            TodoRecordRepository todoRepository, KnowledgeBaseService knowledge) {
        this.eventRepository = eventRepository;
        this.todoRepository = todoRepository;
        this.knowledge = knowledge;
    }

    @Transactional(readOnly = true)
    public WeeklyReportView collect(WeeklyReportRequest request) {
        RequestIdentity identity = RequestIdentityContext.require();
        String reportType = normalizeReportType(request == null ? null : request.reportType());
        ReportWindow window = ReportWindow.of(request, reportType);
        boolean includeCalendar = enabled(request == null ? null : request.includeCalendar(), true);
        boolean includeTodos = enabled(request == null ? null : request.includeTodos(), true);
        boolean includeKnowledge = enabled(request == null ? null : request.includeKnowledge(), false);
        String userNotes = cleanNotes(request == null ? null : request.userNotes());
        List<String> warnings = new ArrayList<>();

        List<CalendarFact> calendarFacts = includeCalendar
                ? collectCalendar(identity, window, warnings)
                : List.of();
        List<TodoFact> todoFacts = includeTodos
                ? collectTodos(identity, window, warnings)
                : List.of();
        SourceView calendarSource = includeCalendar
                ? new SourceView("CALENDAR", "READY", calendarFacts.size(),
                        "已按组织和用户读取，并展开范围内的重复日程。")
                : new SourceView("CALENDAR", "NOT_SELECTED", 0, "用户未选择日历数据源。");
        SourceView todoSource = includeTodos
                ? new SourceView("TODO", "READY", todoFacts.size(),
                        "只纳入截止日期落在所选范围内的待办；WORK_WEEK 会排除周末，无截止日期待办会单独提示。")
                : new SourceView("TODO", "NOT_SELECTED", 0, "用户未选择待办数据源。");
        SourceView knowledgeSource = collectKnowledge(identity, includeKnowledge, warnings);
        SourceView userInputSource = userNotes.isBlank()
                ? new SourceView("USER_INPUT", "NOT_SELECTED", 0, "用户未补充本周重点或风险。")
                : new SourceView("USER_INPUT", "READY", 1, "用户补充将作为 26B 草案输入，不会被当作外部事实。");

        Statistics statistics = Statistics.from(calendarFacts, todoFacts, includeCalendar, includeTodos, window.to());
        if (!includeCalendar) warnings.add("未选择日历数据源，会议数量按 0 统计。");
        if (!includeTodos) warnings.add("未选择待办数据源，待办统计按 0 统计。");

        return new WeeklyReportView("weekly:" + window.from() + ":" + window.to() + ":" + reportType,
                reportType, window.from(), window.to(),
                List.of(calendarSource, todoSource, knowledgeSource, userInputSource),
                calendarFacts, todoFacts, statistics, userNotes, List.copyOf(warnings), Instant.now().toString());
    }

    private List<CalendarFact> collectCalendar(RequestIdentity identity, ReportWindow window,
            List<String> warnings) {
        LocalDateTime start = window.from().atStartOfDay();
        LocalDateTime end = window.to().plusDays(1).atStartOfDay();
        List<CalendarFact> facts = new ArrayList<>();
        for (CalendarEventRecord row : eventRepository
                .findByOrgIdAndUserIdOrderByStartAtAsc(identity.orgId(), identity.userId())) {
            List<IcsParser.CalendarEvent> occurrences = IcsParser.expand(row.toEvent(), start, end);
            for (IcsParser.CalendarEvent occurrence : occurrences) {
                if (!window.includes(occurrence.start().toLocalDate())) continue;
                facts.add(new CalendarFact(row.getId(), occurrence.summary(),
                        occurrence.start().format(DISPLAY), occurrence.end().format(DISPLAY),
                        occurrence.rrule() != null));
            }
        }
        facts.sort(Comparator.comparing(CalendarFact::start));
        return List.copyOf(facts);
    }

    private List<TodoFact> collectTodos(RequestIdentity identity, ReportWindow window, List<String> warnings) {
        List<TodoFact> facts = new ArrayList<>();
        int withoutDue = 0;
        for (TodoRecord row : todoRepository.findByOrgIdAndUserId(identity.orgId(), identity.userId())) {
            TodoTools.Todo todo = row.toTodo();
            if (todo.due() == null || todo.due().isBlank()) {
                withoutDue++;
                continue;
            }
            LocalDate due;
            try {
                due = LocalDate.parse(todo.due());
            } catch (RuntimeException e) {
                warnings.add("有 1 条待办的截止日期无法解析，已排除并保留原始记录。");
                continue;
            }
            if (!due.isBefore(window.from()) && !due.isAfter(window.to()) && window.includes(due)) {
                facts.add(new TodoFact(todo.id(), todo.title(), due.toString(), todo.done(), todo.createdAt()));
            }
        }
        if (withoutDue > 0) {
            warnings.add("有 " + withoutDue + " 条无截止日期待办未纳入本次范围统计。");
        }
        facts.sort(Comparator.comparing(TodoFact::due).thenComparing(TodoFact::createdAt));
        return List.copyOf(facts);
    }

    private SourceView collectKnowledge(RequestIdentity identity, boolean includeKnowledge, List<String> warnings) {
        if (!includeKnowledge) {
            return new SourceView("KNOWLEDGE", "NOT_SELECTED", 0, "未选择资料来源；26A 不执行语义检索。");
        }
        try {
            int count = knowledge.listDocuments().size();
            if (count == 0) {
                warnings.add("知识库已连接但没有可用文档，本次不生成资料结论。");
                return new SourceView("KNOWLEDGE", "EMPTY", 0, "没有可用知识库文档；仅保留日历和待办事实。");
            }
            warnings.add("知识库本阶段只报告可用文档数量，未执行语义检索；26B 再接入引用草案。");
            return new SourceView("KNOWLEDGE", "READY", count, "可用文档已按当前用户和组织过滤。");
        } catch (IllegalStateException e) {
            warnings.add("知识库当前不可用，周报事实未包含资料结论。");
            return new SourceView("KNOWLEDGE", "DEGRADED", 0, "知识库未启用或依赖未就绪，已降级为日历与待办。");
        }
    }

    private static String normalizeReportType(String raw) {
        String value = raw == null || raw.isBlank() ? "WEEKLY" : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("WEEKLY") && !value.equals("WORK_WEEK")) {
            throw new IllegalArgumentException("操作失败：周报类型只支持 WEEKLY 或 WORK_WEEK。");
        }
        return value;
    }

    private static boolean enabled(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String cleanNotes(String raw) {
        if (raw == null || raw.isBlank()) return "";
        if (raw.trim().length() > 2000) {
            throw new IllegalArgumentException("操作失败：周报用户补充不能超过 2000 个字符。");
        }
        return raw.trim();
    }

    public record WeeklyReportRequest(LocalDate from, LocalDate to, String reportType,
            Boolean includeCalendar, Boolean includeTodos, Boolean includeKnowledge, String userNotes) {
    }

    public record WeeklyReportView(String reportKey, String reportType, LocalDate from, LocalDate to,
            List<SourceView> sources, List<CalendarFact> calendar, List<TodoFact> todos,
            Statistics statistics, String userNotes, List<String> warnings, String collectedAt) {
    }

    public record SourceView(String source, String status, int recordCount, String note) {
    }

    public record CalendarFact(Long eventId, String title, String start, String end, boolean recurring) {
    }

    public record TodoFact(String todoId, String title, String due, boolean done, String createdAt) {
    }

    public record Statistics(int meetingCount, int todoCount, int completedTodoCount,
            int pendingTodoCount, int overdueTodoCount) {
        private static Statistics from(List<CalendarFact> calendar, List<TodoFact> todos,
                boolean includeCalendar, boolean includeTodos, LocalDate asOf) {
            int completed = (int) todos.stream().filter(TodoFact::done).count();
            int pending = (int) todos.stream().filter(todo -> !todo.done()).count();
            int overdue = (int) todos.stream()
                    .filter(todo -> !todo.done() && LocalDate.parse(todo.due()).isBefore(asOf))
                    .count();
            return new Statistics(includeCalendar ? calendar.size() : 0,
                    includeTodos ? todos.size() : 0,
                    includeTodos ? completed : 0,
                    includeTodos ? pending : 0,
                    includeTodos ? overdue : 0);
        }
    }

    private record ReportWindow(LocalDate from, LocalDate to, String reportType) {
        private static ReportWindow of(WeeklyReportRequest request, String reportType) {
            if (request == null || request.from() == null || request.to() == null) {
                throw new IllegalArgumentException("操作失败：周报必须提供开始日期和结束日期。");
            }
            if (request.from().isAfter(request.to())) {
                throw new IllegalArgumentException("操作失败：周报开始日期不能晚于结束日期。");
            }
            if (ChronoUnit.DAYS.between(request.from(), request.to()) >= MAX_RANGE_DAYS) {
                throw new IllegalArgumentException("操作失败：周报日期范围不能超过 31 天。");
            }
            return new ReportWindow(request.from(), request.to(), reportType);
        }

        private boolean includes(LocalDate date) {
            return reportType.equals("WEEKLY") || date.getDayOfWeek().getValue() <= 5;
        }
    }
}
