package com.example.vatica.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.example.vatica.config.AppStateProperties;
import com.example.vatica.permission.FileSandboxPolicy;
import com.example.vatica.tool.IcsParser.CalendarEvent;
import com.example.vatica.tool.IcsParser.ParseResult;
import com.example.vatica.tool.IcsParser.Rrule;

/**
 * 日历工具（calendar_query / calendar_create / calendar_import）——迭代 3.5 PIM：日历。
 *
 * <p>迭代 11：本地 ICS 存储从 {@code data/calendar.ics} 迁至 {@code .vatica/calendar.ics}；
 * calendar_import 的源文件走工作区沙盒（用户授权目录）。手写 RFC5545 子集解析见
 * {@link IcsParser}。时区一律按本地时间处理（无 TZID 支持——MVP 边界）。
 *
 * <p><b>幻觉控制（面试核心）</b>：查询结果以"键=值"结构化文本返回，工具描述要求模型
 * "必须原样引用返回中的时间/标题，不得自行推测日程"——具体数据只从工具返回值取。
 */
public final class CalendarTools {

    /** 日历存储文件名（相对内部状态目录）。 */
    public static final String CALENDAR_FILE = "calendar.ics";

    private static final DateTimeFormatter FLEX_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FLEX_DATETIME_T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter FLEX_DATETIME_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path calendarFile;
    private final FileSandboxPolicy sandboxPolicy;

    public CalendarTools(AppStateProperties props, FileSandboxPolicy sandboxPolicy) {
        this.calendarFile = Path.of(props.stateDir()).toAbsolutePath().normalize().resolve(CALENDAR_FILE);
        this.sandboxPolicy = sandboxPolicy;
    }

    @Tool(name = "calendar_query", description = "查询日历在指定日期范围内的日程（含重复日程自动展开到具体日期）。"
            + "日期格式：yyyy-MM-dd（如 2026-08-17）或带时间 yyyy-MM-ddTHH:mm（如 2026-08-17T09:00）。"
            + "返回结构化\"键=值\"文本，必须原样引用其中的时间与标题回答用户，不得自行推测日程内容。")
    public synchronized String query(
            @ToolParam(description = "查询范围开始（含当天），如 2026-08-17", required = true) String start,
            @ToolParam(description = "查询范围结束（含当天），如 2026-08-21；可带时间 2026-08-21T23:59", required = true) String end) {
        LocalDateTime rangeStart = parseFlexible(start, false, "开始");
        LocalDateTime rangeEnd = parseFlexible(end, true, "结束");
        if (!rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException("操作失败：查询结束时间必须晚于开始时间。");
        }
        List<CalendarEvent> events = loadEvents();
        List<CalendarEvent> hits = new ArrayList<>();
        for (CalendarEvent e : events) {
            hits.addAll(IcsParser.expand(e, rangeStart, rangeEnd));
        }
        hits.sort(Comparator.comparing(CalendarEvent::start));
        if (hits.isEmpty()) {
            return "该日期范围内没有日程。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(hits.size()).append(" 条日程\n");
        int i = 1;
        for (CalendarEvent e : hits) {
            sb.append(i++).append(") 标题=").append(e.summary()).append('\n');
            if (isAllDay(e)) {
                sb.append("   时间=").append(e.start().toLocalDate()).append("（全天）\n");
            } else {
                sb.append("   开始=").append(IcsParser.display(e.start())).append('\n')
                        .append("   结束=").append(IcsParser.display(e.end())).append('\n');
            }
            if (e.rrule() != null) {
                sb.append("   重复=").append(e.rrule().describe()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 全天判定：0 点开始且时长约 24 小时（23:59:59 或次日 0 点结束都算）。 */
    private static boolean isAllDay(CalendarEvent e) {
        long hours = java.time.Duration.between(e.start(), e.end()).toHours();
        return e.start().toLocalTime().equals(LocalTime.MIDNIGHT) && hours >= 23 && hours <= 25;
    }

    @Tool(name = "calendar_create", description = "在日历中创建一条日程并保存到本地 ICS 文件。"
            + "日期格式 yyyy-MM-dd（全天）或 yyyy-MM-ddTHH:mm（带时间）。"
            + "重复规则可选：FREQ=DAILY 或 FREQ=WEEKLY，且必须带 COUNT=n（次数）或 UNTIL=yyyyMMdd（截止），"
            + "如 \"FREQ=WEEKLY;COUNT=4\"。创建前若用户未明确要求重复，不要加重复规则。")
    public synchronized String create(
            @ToolParam(description = "日程标题，如\"项目周会\"", required = true) String summary,
            @ToolParam(description = "开始时间，如 2026-08-17 或 2026-08-17T10:00", required = true) String start,
            @ToolParam(description = "结束时间，同开始格式；省略时默认开始后 1 小时", required = false) String end,
            @ToolParam(description = "重复规则（可选），如 \"FREQ=WEEKLY;COUNT=4\"；不带则创建单次日程", required = false) String rrule) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("操作失败：日程标题不能为空。");
        }
        LocalDateTime s = parseFlexible(start, false, "开始");
        LocalDateTime e = end == null || end.isBlank()
                ? s.plusHours(1)
                : parseFlexibleEnd(end); // 纯日期按"当天结束"理解：全天日程天然合法
        if (!e.isAfter(s)) {
            throw new IllegalArgumentException("操作失败：结束时间必须晚于开始时间。");
        }
        Rrule r = rrule == null || rrule.isBlank() ? null : IcsParser.parseRrule(rrule);
        CalendarEvent event = new CalendarEvent(summary.trim(), s, e, r);

        List<CalendarEvent> events = loadEvents();
        events.add(event);
        saveEvents(events);

        StringBuilder sb = new StringBuilder("已创建日程：\n标题=").append(event.summary()).append('\n')
                .append("开始=").append(IcsParser.display(s)).append('\n')
                .append("结束=").append(IcsParser.display(e)).append('\n');
        if (r != null) {
            sb.append("重复=").append(r.describe()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "calendar_import", description = "从已授权工作目录内的 .ics 文件导入日程，合并进本地日历（calendar.ics）。"
            + "支持 RFC5545 基础子集：SUMMARY/DTSTART/DTEND + FREQ=DAILY|WEEKLY 重复规则；"
            + "复杂重复规则（INTERVAL/BYDAY 等）会降级为单次日程并在返回中说明。")
    public synchronized String importFrom(
            @ToolParam(description = "待导入的 .ics 文件路径，如 \"日程备份.ics\"；必须位于已授权工作目录内", required = true) String sourcePath) {
        Path source = sandboxPolicy.resolveForRead(sourcePath);
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("操作失败：文件不存在。请先调用 list_files 确认路径。");
        }
        String text;
        try {
            text = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("操作失败：读取 ICS 文件失败。" + ex.getMessage(), ex);
        }
        ParseResult parsed = IcsParser.parse(text);
        if (parsed.events().isEmpty()) {
            throw new IllegalArgumentException("操作失败：文件中没有可导入的日程（VEVENT）。请确认是有效的 .ics 文件。");
        }
        List<CalendarEvent> events = loadEvents();
        events.addAll(parsed.events());
        saveEvents(events);

        StringBuilder sb = new StringBuilder("已导入 ").append(parsed.events().size())
                .append(" 条日程（本地日历现有 ").append(events.size()).append(" 条）");
        if (parsed.problemCount() > 0) {
            sb.append("；其中 ").append(parsed.problemCount()).append(" 条异常（无开始时间已跳过 / 复杂重复规则已按单次日程处理）");
        }
        return sb.toString();
    }

    // ══════════════════════════════ 存储与解析 ══════════════════════════════

    private List<CalendarEvent> loadEvents() {
        if (!Files.exists(calendarFile)) {
            return new ArrayList<>();
        }
        try {
            return IcsParser.parse(Files.readString(calendarFile, StandardCharsets.UTF_8)).events();
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：读取日历文件失败。" + e.getMessage(), e);
        }
    }

    private void saveEvents(List<CalendarEvent> events) {
        try {
            // 迭代 10 I10-5：工作目录可能还不存在 data/，先建父目录再写
            Path parent = calendarFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(calendarFile, IcsParser.toIcs(events), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：保存日历文件失败。" + e.getMessage(), e);
        }
    }

    /** 宽松日期解析：yyyy-MM-dd / yyyy-MM-ddTHH:mm / yyyy-MM-dd HH:mm；纯日期按参数决定是当日 0 点还是 23:59:59。 */
    static LocalDateTime parseFlexible(String value, boolean endOfDay, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("操作失败：" + what + "时间不能为空。");
        }
        String v = value.trim();
        try {
            LocalDate date = LocalDate.parse(v, FLEX_DATE);
            return endOfDay ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // 继续尝试带时间的格式
        }
        for (DateTimeFormatter f : new DateTimeFormatter[] { FLEX_DATETIME_T, FLEX_DATETIME_SPACE }) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        throw new IllegalArgumentException("操作失败：" + what + "时间格式无法解析（" + v
                + "）。支持格式：yyyy-MM-dd 或 yyyy-MM-ddTHH:mm。");
    }

    /** 结束时间解析：纯日期 → 当天 23:59:59（全天/跨天日程的自然语义）。 */
    static LocalDateTime parseFlexibleEnd(String value) {
        return parseFlexible(value, true, "结束");
    }
}
