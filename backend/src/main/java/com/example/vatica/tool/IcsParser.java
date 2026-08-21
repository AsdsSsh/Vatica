package com.example.vatica.tool;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 手写 iCalendar（RFC5545）子集解析器——迭代 3.5 日历工具核心。
 *
 * <p><b>支持范围（范围控制，面试可讲）</b>：
 * <ul>
 *   <li>VEVENT：SUMMARY / DTSTART / DTEND</li>
 *   <li>DTSTART/DTEND 两种形态：日期（yyyyMMdd）、本地日期时间（yyyyMMddTHHmmss，可带 Z 后缀）</li>
 *   <li>基础 RRULE：FREQ=DAILY|WEEKLY + COUNT 或 UNTIL（两者必带其一，防无限展开）</li>
 *   <li>属性参数（如 DTSTART;VALUE=DATE / ;TZID=...）取属性名与值，参数忽略；时区一律按本地时间处理</li>
 * </ul>
 * <b>明确不做</b>：INTERVAL/BYDAY 等复杂重复、VTIMEZONE 全量、VALARM——MVP 边界，
 * 复杂规则是"后续可扩展"而非"没想过"。
 *
 * <p>安全护栏：单事件重复展开上限 500 次（防病态 RRULE 打爆内存）；查询窗口外的起点
 * 用"快进"跳过（远古起点的每日重复不会逐日步进）。
 */
public final class IcsParser {

    /** 单事件重复展开次数护栏。 */
    public static final int MAX_OCCURRENCES = 500;

    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ICS_DATETIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 一条日程：开始/结束为本地时间；rrule 为 null 表示单次日程。 */
    public record CalendarEvent(String summary, LocalDateTime start, LocalDateTime end, Rrule rrule) {
        public CalendarEvent {
            summary = (summary == null || summary.isBlank()) ? "（无标题）" : summary;
        }
    }

    /** 基础重复规则：freq='D'（每天）或 'W'（每周）；count 与 until 至少一个非空。 */
    public record Rrule(char freq, Integer count, LocalDate until) {
        public Rrule {
            if (freq != 'D' && freq != 'W') {
                throw new IllegalArgumentException("操作失败：重复规则只支持 FREQ=DAILY 或 FREQ=WEEKLY。");
            }
            if (count == null && until == null) {
                throw new IllegalArgumentException("操作失败：重复规则必须带 COUNT（次数）或 UNTIL（截止日期），防止无限重复。");
            }
            if (count != null && count <= 0) {
                throw new IllegalArgumentException("操作失败：COUNT 必须为正整数。");
            }
        }

        /** 步进天数。 */
        public int stepDays() {
            return freq == 'D' ? 1 : 7;
        }

        /** 给人/模型读的中文描述。 */
        public String describe() {
            StringBuilder sb = new StringBuilder(freq == 'D' ? "每天" : "每周");
            if (count != null) {
                sb.append("（共").append(count).append("次）");
            }
            if (until != null) {
                sb.append("（截止 ").append(until).append("）");
            }
            return sb.toString();
        }

        public String toIcs() {
            StringBuilder sb = new StringBuilder("RRULE:FREQ=").append(freq == 'D' ? "DAILY" : "WEEKLY");
            if (count != null) {
                sb.append(";COUNT=").append(count);
            }
            if (until != null) {
                sb.append(";UNTIL=").append(until.format(ICS_DATE));
            }
            return sb.toString();
        }
    }

    /** 解析结果：events 为成功解析的日程；problemCount 为异常数（无 DTSTART 跳过 + RRULE 解析失败降级）。 */
    public record ParseResult(List<CalendarEvent> events, int problemCount) {
    }

    private IcsParser() {
    }

    // ══════════════════════════════ 解析 ══════════════════════════════

    /** 解析整份 ICS 文本（可含多个 VEVENT；无 BEGIN:VCALENDAR 包裹的裸 VEVENT 也接受）。 */
    public static ParseResult parse(String text) {
        List<CalendarEvent> events = new ArrayList<>();
        int problems = 0;
        List<String> lines = unfold(text);
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).equalsIgnoreCase("BEGIN:VEVENT")) {
                continue;
            }
            StringBuilder block = new StringBuilder();
            int j = i + 1;
            for (; j < lines.size() && !lines.get(j).equalsIgnoreCase("END:VEVENT"); j++) {
                block.append(lines.get(j)).append('\n');
            }
            i = j;
            EventParts parts = parseBlock(block.toString());
            if (parts.start == null) {
                problems++;
                continue;
            }
            Rrule rrule = null;
            if (parts.rruleRaw != null) {
                try {
                    rrule = parseRrule(parts.rruleRaw);
                } catch (IllegalArgumentException e) {
                    problems++; // 导入场景降级为单次日程，由调用方在结果中如实说明
                }
            }
            LocalDateTime end = parts.end != null ? parts.end : parts.start.plusHours(1);
            events.add(new CalendarEvent(parts.summary, parts.start, end, rrule));
        }
        return new ParseResult(events, problems);
    }

    private record EventParts(String summary, LocalDateTime start, LocalDateTime end, String rruleRaw) {
    }

    private static EventParts parseBlock(String block) {
        String summary = null;
        LocalDateTime start = null;
        LocalDateTime end = null;
        String rrule = null;
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon);
            int semi = name.indexOf(';');
            String key = (semi >= 0 ? name.substring(0, semi) : name).toUpperCase(Locale.ROOT);
            String value = line.substring(colon + 1);
            switch (key) {
                case "SUMMARY" -> summary = unescape(value);
                case "DTSTART" -> start = parseDateTime(value);
                case "DTEND" -> end = parseDateTime(value);
                case "RRULE" -> rrule = value.trim();
                default -> {
                    // 其他属性（UID/DESCRIPTION/VALARM...）不在子集内，忽略
                }
            }
        }
        return new EventParts(summary, start, end, rrule);
    }

    /** 解析 DTSTART/DTEND 值：日期或本地日期时间，可选 Z 后缀（按本地时间处理）。 */
    static LocalDateTime parseDateTime(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.endsWith("Z")) {
            v = v.substring(0, v.length() - 1);
        }
        try {
            if (v.matches("\\d{8}")) {
                return LocalDate.parse(v, ICS_DATE).atStartOfDay();
            }
            if (v.matches("\\d{8}T\\d{6}")) {
                return LocalDateTime.parse(v, ICS_DATETIME);
            }
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
        return null;
    }

    /** 解析基础 RRULE："FREQ=WEEKLY;COUNT=4" / "FREQ=DAILY;UNTIL=20260831"（键大小写不敏感）。 */
    public static Rrule parseRrule(String raw) {
        char freq = 0;
        Integer count = null;
        LocalDate until = null;
        try {
            for (String part : raw.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim();
                switch (key) {
                    case "FREQ" -> freq = switch (value.toUpperCase(Locale.ROOT)) {
                        case "DAILY" -> 'D';
                        case "WEEKLY" -> 'W';
                        default -> 0;
                    };
                    case "COUNT" -> count = Integer.parseInt(value);
                    case "UNTIL" -> {
                        String digits = value.replaceAll("\\D", "");
                        if (digits.length() < 8) {
                            throw new IllegalArgumentException("操作失败：UNTIL 格式应为 yyyyMMdd。");
                        }
                        until = LocalDate.parse(digits.substring(0, 8), ICS_DATE);
                    }
                    default -> {
                        // INTERVAL/BYDAY 等不在子集内：忽略该部分
                    }
                }
            }
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("操作失败：重复规则无法解析（" + raw + "）。支持形态：FREQ=DAILY|WEEKLY 加 COUNT=n 或 UNTIL=yyyyMMdd。", e);
        }
        return new Rrule(freq, count, until);
    }

    /** RFC5545 行展开：以空格/Tab 开头的行是上一行的续行。 */
    private static List<String> unfold(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    /** TEXT 值反转义：\\ \; \, \n / \N。 */
    private static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                sb.append(switch (next) {
                    case 'n', 'N' -> '\n';
                    case ';' -> ';';
                    case ',' -> ',';
                    default -> next;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ══════════════════════════════ 序列化 ══════════════════════════════

    /** 生成完整 VCALENDAR 文本（含全部事件）。 */
    public static String toIcs(List<CalendarEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\n");
        sb.append("VERSION:2.0\n");
        sb.append("PRODID:-//Vatica//PIM 1.0//CN\n");
        for (CalendarEvent e : events) {
            appendEvent(sb, e);
        }
        sb.append("END:VCALENDAR\n");
        return sb.toString();
    }

    private static void appendEvent(StringBuilder sb, CalendarEvent e) {
        sb.append("BEGIN:VEVENT\n");
        sb.append("UID:").append(UUID.randomUUID()).append("@vatica.local\n");
        sb.append("DTSTART:").append(formatDateTime(e.start())).append('\n');
        sb.append("DTEND:").append(formatDateTime(e.end())).append('\n');
        sb.append("SUMMARY:").append(escape(e.summary())).append('\n');
        if (e.rrule() != null) {
            sb.append(e.rrule().toIcs()).append('\n');
        }
        sb.append("END:VEVENT\n");
    }

    /** 午夜整点事件写成日期形态（yyyyMMdd），其余写日期时间。 */
    private static String formatDateTime(LocalDateTime t) {
        if (t.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return t.format(ICS_DATE);
        }
        return t.format(ICS_DATETIME);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,");
    }

    // ══════════════════════════════ 重复展开 ══════════════════════════════

    /** 展开事件在 [rangeStart, rangeEnd]（左闭右开重叠判定）内的所有发生。 */
    /** 对外提供确定性的重复日程展开，供周报等只读聚合场景复用同一套范围语义。 */
    public static List<CalendarEvent> expand(CalendarEvent event, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        Rrule r = event.rrule();
        if (r == null) {
            LocalDateTime end = event.end();
            boolean overlaps = event.start().isBefore(rangeEnd) && end.isAfter(rangeStart);
            return overlaps ? List.of(event) : List.of();
        }
        Duration duration = Duration.between(event.start(), event.end());
        int stepDays = r.stepDays();
        LocalDateTime t = event.start();
        long consumed = 0;

        // 快进：查询窗口起点前的重复直接从起点跳到窗口附近，避免远古起点逐日步进
        if (t.isBefore(rangeStart)) {
            long gapDays = Duration.between(
                    t.toLocalDate().atStartOfDay(), rangeStart.toLocalDate().atStartOfDay()).toDays();
            long skip = gapDays / stepDays;
            consumed += skip;
            t = t.plusDays(skip * stepDays);
        }

        List<CalendarEvent> out = new ArrayList<>();
        while (!t.isAfter(rangeEnd)) {
            if (consumed >= MAX_OCCURRENCES) {
                break; // 护栏：病态规则截断
            }
            if (r.count() != null && consumed >= r.count()) {
                break;
            }
            if (r.until() != null && t.toLocalDate().isAfter(r.until())) {
                break;
            }
            LocalDateTime end = t.plus(duration);
            if (t.isBefore(rangeEnd) && end.isAfter(rangeStart)) {
                out.add(new CalendarEvent(event.summary(), t, end, r));
            }
            consumed++;
            t = t.plusDays(stepDays);
        }
        return out;
    }

    /** 统一展示格式（键=值 排版用）。 */
    static String display(LocalDateTime t) {
        return t.format(DISPLAY);
    }
}
