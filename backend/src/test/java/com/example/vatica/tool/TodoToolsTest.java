package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import com.example.vatica.config.AppStateProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 待办工具真实 IO 单测（迭代 3.5）：@TempDir 注入白名单目录，测增删查改、排序、
 * 提醒窗口（相对系统今天计算）、跨实例持久化、数据损坏防护。
 */
class TodoToolsTest {

    @TempDir
    Path tempDir;

    TodoTools todoTools;

    @BeforeEach
    void setUp() {
        todoTools = new TodoTools(new AppStateProperties(tempDir.toString()));
    }

    /** 添加 → 列表可见，返回 id 且标题/状态正确 */
    @Test
    void add_thenList_showsPending() {
        String added = todoTools.add("准备项目周会材料", "2026-08-17");

        assertThat(added).contains("已添加待办").contains("标题=准备项目周会材料").contains("截止=2026-08-17");
        String id = added.substring(added.indexOf("id=") + 3, added.indexOf('\n', added.indexOf("id=")));

        String listed = todoTools.list();
        assertThat(listed).contains("共 1 条待办（未完成 1 条）")
                .contains("id=" + id)
                .contains("状态=未完成");
    }

    /** 空标题 → 报错 */
    @Test
    void add_blankTitle_throws() {
        assertThatThrownBy(() -> todoTools.add("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题");
    }

    /** 截止日期格式错误 → 报错 */
    @Test
    void add_badDue_throws() {
        assertThatThrownBy(() -> todoTools.add("任务", "2026/08/17"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    /** 标题中的换行被清理（防破坏行式输出） */
    @Test
    void add_newlinesInTitle_sanitized() {
        todoTools.add("第一行\n第二行", null);

        String listed = todoTools.list();
        assertThat(listed).contains("标题=第一行 第二行");
        assertThat(listed).doesNotContain("\n第二行");
    }

    /** 完成 → 状态翻转 */
    @Test
    void complete_marksDone() {
        String added = todoTools.add("写周报", null);
        String id = extractId(added);

        String done = todoTools.complete(id);

        assertThat(done).contains("已完成待办").contains("写周报");
        assertThat(todoTools.list()).contains("状态=已完成").contains("未完成 0 条");
    }

    /** 未知 id → 报错并列出可用 id */
    @Test
    void complete_unknownId_throwsWithIds() {
        String added = todoTools.add("任务A", null);
        String id = extractId(added);

        assertThatThrownBy(() -> todoTools.complete("nope1234"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到")
                .hasMessageContaining(id);
    }

    /** 已完成再完成 → 幂等提示，不报错 */
    @Test
    void complete_alreadyDone_returnsNotice() {
        String id = extractId(todoTools.add("任务B", null));
        todoTools.complete(id);

        assertThat(todoTools.complete(id)).contains("已是完成状态");
    }

    /** 排序：未完成在前，按截止日期升序，无截止排最后 */
    @Test
    void list_sortsPendingFirstByDue() {
        todoTools.add("无截止任务", null);
        todoTools.add("稍晚任务", "2026-09-01");
        todoTools.add("紧急任务", "2026-08-20");
        todoTools.complete(extractId(todoTools.add("已完成任务", "2026-08-01")));

        String listed = todoTools.list();
        assertThat(listed.indexOf("紧急任务")).isLessThan(listed.indexOf("稍晚任务"));
        assertThat(listed.indexOf("稍晚任务")).isLessThan(listed.indexOf("无截止任务"));
        assertThat(listed.indexOf("无截止任务")).isLessThan(listed.indexOf("已完成任务"));
    }

    /** 提醒：今天到期与已逾期被标出，窗口外的不列 */
    @Test
    void remind_listsDueSoonAndOverdue() {
        LocalDate today = LocalDate.now();
        todoTools.add("今天到期", today.toString());
        todoTools.add("已逾期", today.minusDays(2).toString());
        todoTools.add("3 天后", today.plusDays(3).toString());
        todoTools.add("30 天后", today.plusDays(30).toString());

        String reminded = todoTools.remind(7);

        assertThat(reminded).contains("共 3 条")
                .contains("今天到期")
                .contains("已逾期 2 天")
                .contains("3 天后到期")
                .doesNotContain("30 天后");
    }

    /** 已完成的不出现在提醒里 */
    @Test
    void remind_excludesDone() {
        LocalDate today = LocalDate.now();
        String id = extractId(todoTools.add("已完成的急事", today.toString()));
        todoTools.complete(id);

        assertThat(todoTools.remind(7)).contains("没有需要关注的待办");
    }

    /** 默认窗口 7 天；窗口外的返回提示 */
    @Test
    void remind_defaultWindow() {
        assertThat(todoTools.remind(null)).contains("未来 7 天内没有需要关注的待办");
    }

    /** 跨实例持久化：新实例读到同一 JSON 文件 */
    @Test
    void persistsAcrossInstances() {
        todoTools.add("持久化任务", "2026-08-30");

        TodoTools fresh = new TodoTools(new AppStateProperties(tempDir.toString()));
        assertThat(fresh.list()).contains("持久化任务");
    }

    /** 数据文件损坏 → 明确报错而不是静默清空 */
    @Test
    void corruptJson_throwsInsteadOfWiping() throws Exception {
        Files.writeString(tempDir.resolve(TodoTools.TODO_FILE), "{这不是JSON[");

        assertThatThrownBy(() -> todoTools.list())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("损坏");
    }

    /** 迭代 10 I10-5：工作目录（data/）不存在时首次添加自动创建父目录并成功落盘。 */
    @Test
    void add_createsMissingDataDirectory() {
        Path nested = tempDir.resolve("data");
        TodoTools fresh = new TodoTools(new AppStateProperties(nested.toString()));

        fresh.add("首个待办", "2026-08-30");

        assertThat(nested.resolve(TodoTools.TODO_FILE)).exists();
        assertThat(fresh.list()).contains("首个待办");
    }

    private static String extractId(String addResult) {
        String marker = "id=";
        int start = addResult.indexOf(marker) + marker.length();
        int end = addResult.indexOf('\n', start);
        return addResult.substring(start, end < 0 ? addResult.length() : end);
    }
}
