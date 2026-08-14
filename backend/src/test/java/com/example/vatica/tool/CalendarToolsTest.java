package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 日历工具真实 IO 单测（迭代 3.5）：@TempDir 注入白名单目录，测 ICS 解析、RRULE 展开、
 * 创建/导入落盘、路径安全。时间全部用固定日期（不依赖系统时钟）。
 */
class CalendarToolsTest {

    @TempDir
    Path tempDir;

    CalendarTools calendarTools;

    @BeforeEach
    void setUp() {
        calendarTools = new CalendarTools(new FileToolProperties(tempDir.toString(), 1024));
    }

    // ══════════════ 创建与查询 ══════════════

    /** 创建带时间日程 → 查询范围内可见，内容与创建参数一致 */
    @Test
    void create_thenQuery_roundTrip() {
        String created = calendarTools.create("项目周会", "2026-08-17T10:00", "2026-08-17T11:00", null);

        assertThat(created).contains("已创建日程").contains("标题=项目周会").contains("开始=2026-08-17 10:00");

        String queried = calendarTools.query("2026-08-17", "2026-08-18");
        assertThat(queried).contains("共 1 条日程").contains("标题=项目周会").contains("结束=2026-08-17 11:00");
    }

    /** 全天日程（纯日期）→ 存储为日期形态，查询显示"（全天）" */
    @Test
    void create_allDayEvent_usesDateForm() {
        calendarTools.create("全天培训", "2026-08-20", "2026-08-20", null);

        String queried = calendarTools.query("2026-08-20", "2026-08-20");
        assertThat(queried).contains("标题=全天培训").contains("时间=2026-08-20（全天）");

        assertThat(readCalendarFile()).contains("DTSTART:20260820");
    }

    /** 结束时间省略 → 默认开始后 1 小时 */
    @Test
    void create_endOmitted_defaultsOneHour() {
        calendarTools.create("站立会", "2026-08-17T09:30", null, null);

        String queried = calendarTools.query("2026-08-17", "2026-08-17");
        assertThat(queried).contains("结束=2026-08-17 10:30");
    }

    /** 重复日程（每周共 4 次）→ 查询窗口自动展开到具体日期 */
    @Test
    void query_expandsWeeklyCount() {
        calendarTools.create("项目周会", "2026-08-17T10:00", "2026-08-17T11:00", "FREQ=WEEKLY;COUNT=4");

        String week1 = calendarTools.query("2026-08-17", "2026-08-21");
        assertThat(week1).contains("共 1 条日程");

        String month = calendarTools.query("2026-08-17", "2026-09-13");
        assertThat(month).contains("共 4 条日程")
                .contains("开始=2026-08-17 10:00")
                .contains("开始=2026-08-24 10:00")
                .contains("开始=2026-08-31 10:00")
                .contains("开始=2026-09-07 10:00")
                .contains("重复=每周（共4次）");
    }

    /** 重复日程（每天到截止日期）→ UNTIL 边界正确（截止当天包含、次日不包含） */
    @Test
    void query_expandsDailyUntil() {
        calendarTools.create("每日站会", "2026-08-17T09:00", "2026-08-17T09:30", "FREQ=DAILY;UNTIL=20260819");

        String inRange = calendarTools.query("2026-08-17", "2026-08-19");
        assertThat(inRange).contains("共 3 条日程");

        String outOfRange = calendarTools.query("2026-08-20", "2026-08-20");
        assertThat(outOfRange).contains("没有日程");
    }

    /** 重叠判定左闭右开：11:00 结束的事件不出现在 11:00 开始的查询窗口 */
    @Test
    void query_halfOpenBoundary_noFalseHit() {
        calendarTools.create("例会", "2026-08-17T10:00", "2026-08-17T11:00", null);

        assertThat(calendarTools.query("2026-08-17T11:00", "2026-08-17T12:00")).contains("没有日程");
        assertThat(calendarTools.query("2026-08-17T10:59", "2026-08-17T11:00")).contains("共 1 条日程");
    }

    /** 空范围 → 友好提示 */
    @Test
    void query_emptyRange_returnsNone() {
        assertThat(calendarTools.query("2026-08-17", "2026-08-21")).contains("没有日程");
    }

    // ══════════════ 导入 ══════════════

    /** 导入含日期形态 DTSTART、转义 SUMMARY、重复规则的 ICS → 全部解析正确 */
    @Test
    void import_parsesDateFormEscapesAndRrule() throws Exception {
        String ics = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:test-1
                DTSTART:20260818
                SUMMARY:季度汇报\\;含逗号\\,转义
                END:VEVENT
                BEGIN:VEVENT
                UID:test-2
                DTSTART;TZID=Asia/Shanghai:20260819T090000
                DTEND:20260819T100000
                SUMMARY:带时区参数的事件
                RRULE:FREQ=WEEKLY;COUNT=2
                END:VEVENT
                END:VCALENDAR
                """;
        Files.writeString(tempDir.resolve("backup.ics"), ics);

        String result = calendarTools.importFrom("backup.ics");

        assertThat(result).contains("已导入 2 条日程");
        String queried = calendarTools.query("2026-08-18", "2026-08-19");
        assertThat(queried)
                .contains("标题=季度汇报;含逗号,转义")
                .contains("开始=2026-08-18 00:00")
                .contains("标题=带时区参数的事件")
                .contains("开始=2026-08-19 09:00");
    }

    /** 复杂重复规则（MONTHLY）→ 降级单次日程并在结果中说明 */
    @Test
    void import_complexRrule_degradesWithNotice() throws Exception {
        Files.writeString(tempDir.resolve("complex.ics"), """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:c-1
                DTSTART:20260817T100000
                SUMMARY:月度复盘
                RRULE:FREQ=MONTHLY;COUNT=3
                END:VEVENT
                END:VCALENDAR
                """);

        String result = calendarTools.importFrom("complex.ics");

        assertThat(result).contains("已导入 1 条日程").contains("1 条异常");
        assertThat(calendarTools.query("2026-08-17", "2026-08-17")).contains("标题=月度复盘");
        assertThat(calendarTools.query("2026-09-17", "2026-09-17")).contains("没有日程");
    }

    /** 无 VEVENT 的文件 → 报错 */
    @Test
    void import_noVevent_throws() throws Exception {
        Files.writeString(tempDir.resolve("empty.ics"), "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR\n");

        assertThatThrownBy(() -> calendarTools.importFrom("empty.ics"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VEVENT");
    }

    /** 文件不存在 → 报错 */
    @Test
    void import_missingFile_throws() {
        assertThatThrownBy(() -> calendarTools.importFrom("不存在.ics"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    /** 白名单外路径 → 拒绝 */
    @Test
    void import_traversalRejected() {
        assertThatThrownBy(() -> calendarTools.importFrom("../secret.ics"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已授权目录");
    }

    // ══════════════ 参数校验 ══════════════

    @Test
    void create_blankSummary_throws() {
        assertThatThrownBy(() -> calendarTools.create("  ", "2026-08-17", "2026-08-17", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题");
    }

    @Test
    void create_endBeforeStart_throws() {
        assertThatThrownBy(() -> calendarTools.create("会议", "2026-08-17T10:00", "2026-08-17T09:00", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("晚于");
    }

    @Test
    void create_badDate_throws() {
        assertThatThrownBy(() -> calendarTools.create("会议", "8月17日", "2026-08-17", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式");
    }

    @Test
    void create_badRrule_throws() {
        assertThatThrownBy(() -> calendarTools.create("会议", "2026-08-17", "2026-08-17", "FREQ=YEARLY;COUNT=2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FREQ");
    }

    /** 无限重复（无 COUNT 无 UNTIL）→ 拒绝 */
    @Test
    void create_rruleWithoutBound_throws() {
        assertThatThrownBy(() -> calendarTools.create("会议", "2026-08-17", "2026-08-17", "FREQ=DAILY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COUNT");
    }

    @Test
    void query_endBeforeStart_throws() {
        assertThatThrownBy(() -> calendarTools.query("2026-08-21", "2026-08-17"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("晚于");
    }

    /** 多次创建累积到同一日历文件，ICS 文本合法 */
    @Test
    void create_multipleEvents_appendToSameFile() {
        calendarTools.create("日程一", "2026-08-17", "2026-08-17", null);
        calendarTools.create("日程二", "2026-08-18", "2026-08-18", null);

        String ics = readCalendarFile();
        assertThat(ics).contains("BEGIN:VCALENDAR").contains("SUMMARY:日程一").contains("SUMMARY:日程二");
        assertThat(calendarTools.query("2026-08-17", "2026-08-18")).contains("共 2 条日程");
    }

    private String readCalendarFile() {
        try {
            return Files.readString(tempDir.resolve(CalendarTools.CALENDAR_FILE));
        } catch (Exception e) {
            throw new AssertionError("读取日历文件失败", e);
        }
    }
}
