package com.example.vatica.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import com.example.vatica.config.AppStateProperties;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 待办工具（todo_add / todo_list / todo_complete / todo_remind）——迭代 3.5 PIM：待办。
 *
 * <p>迭代 11：本地 JSON 存储从 {@code data/todos.json} 迁至 {@code .vatica/todos.json}；
 * 数据损坏时报错而非静默清空（避免用户数据丢失）。
 *
 * <p>todo_remind 是"提醒"语义：列出截止时间在 N 天内（含已逾期）的未完成待办，
 * 供模型转述提醒用户——提醒时机、方式（邮件/弹窗）属后续扩展，本迭代只做数据层。
 */
public final class TodoTools {

    /** 待办存储文件名（相对内部状态目录）。 */
    public static final String TODO_FILE = "todos.json";

    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 单条待办：due 为 null 表示无截止日期。 */
    public record Todo(String id, String title, String due, boolean done, String createdAt) {
    }

    private final Path todoFile;
    private final TodoRecordRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public TodoTools(AppStateProperties props) {
        this.todoFile = Path.of(props.stateDir()).toAbsolutePath().normalize().resolve(TODO_FILE);
        this.repository = null;
    }

    /** 迭代 14 生产存储：不再读取共享 .vatica/todos.json。 */
    public TodoTools(TodoRecordRepository repository) {
        this.todoFile = null;
        this.repository = repository;
    }

    @Tool(name = "todo_add", description = "添加一条待办到本地清单。截止日期可选（yyyy-MM-dd）。"
            + "返回新待办的 id，后续可用 todo_complete 通过 id 标记完成。")
    public synchronized String add(
            @ToolParam(name = "title", description = "待办标题，如\"准备项目周会材料\"", required = true) String title,
            @ToolParam(name = "due", description = "截止日期（可选），如 2026-08-17", required = false) String due) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("操作失败：待办标题不能为空。");
        }
        String cleanTitle = title.trim().replace('\n', ' ').replace('\r', ' ');
        String dueDate = null;
        if (due != null && !due.isBlank()) {
            try {
                dueDate = LocalDate.parse(due.trim(), DUE).toString();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("操作失败：截止日期格式应为 yyyy-MM-dd（如 2026-08-17）。", e);
            }
        }
        Todo todo = new Todo(UUID.randomUUID().toString().substring(0, 8), cleanTitle, dueDate, false,
                LocalDateTime.now().format(CREATED));
        List<Todo> todos = load();
        todos.add(todo);
        save(todos);
        return "已添加待办：\nid=" + todo.id() + "\n标题=" + todo.title()
                + (dueDate == null ? "" : "\n截止=" + dueDate);
    }

    @Tool(name = "todo_list", description = "列出全部待办（含已完成），未完成在前、按截止日期升序。"
            + "每条含 id/标题/状态/截止日期；回答用户时请基于此列表，不要自行编造待办内容。")
    public synchronized String list() {
        List<Todo> todos = load();
        if (todos.isEmpty()) {
            return "待办清单为空。可用 todo_add 添加待办。";
        }
        todos.sort(Comparator.comparing(Todo::done)
                .thenComparing(t -> t.due() == null, Boolean::compareTo)
                .thenComparing(t -> t.due() == null ? "" : t.due())
                .thenComparing(Todo::createdAt));
        long pending = todos.stream().filter(t -> !t.done()).count();
        StringBuilder sb = new StringBuilder("共 ").append(todos.size()).append(" 条待办（未完成 ")
                .append(pending).append(" 条）\n");
        for (Todo t : todos) {
            sb.append("- id=").append(t.id())
                    .append(" 标题=").append(t.title())
                    .append(" 状态=").append(t.done() ? "已完成" : "未完成")
                    .append(t.due() == null ? "" : " 截止=" + t.due())
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "todo_complete", description = "按 id 把一条待办标记为已完成。id 来自 todo_add 的返回值或 todo_list 的列表。")
    public synchronized String complete(
            @ToolParam(name = "id", description = "待办 id（如 todo_add 返回的 id）", required = true) String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("操作失败：待办 id 不能为空。请先从 todo_list 获取 id。");
        }
        List<Todo> todos = load();
        Todo target = todos.stream().filter(t -> t.id().equals(id.trim())).findFirst().orElse(null);
        if (target == null) {
            String ids = todos.isEmpty() ? "（当前没有待办）"
                    : String.join(", ", todos.stream().map(Todo::id).limit(10).toList());
            throw new IllegalArgumentException("操作失败：未找到 id=" + id.trim() + " 的待办。请先用 todo_list 查询现有待办（已有 id："
                    + ids + "）。");
        }
        if (target.done()) {
            return "该待办（" + target.title() + "）已是完成状态，无需重复操作。";
        }
        todos.replaceAll(t -> t.id().equals(target.id()) ? new Todo(t.id(), t.title(), t.due(), true, t.createdAt()) : t);
        save(todos);
        return "已完成待办：id=" + target.id() + " 标题=" + target.title();
    }

    @Tool(name = "todo_remind", description = "列出需要在 N 天内关注的未完成待办（含已逾期），按截止日期升序。"
            + "用于提醒用户到期事项；返回中已标注\"已逾期 X 天 / 今天到期 / X 天后到期\"，请原样转述。")
    public synchronized String remind(
            @ToolParam(name = "days", description = "未来 N 天窗口（可选，默认 7），如 3 表示只关注 3 天内到期（含逾期）的待办",
                    required = false) Integer days) {
        int window = days == null || days <= 0 ? 7 : Math.min(days, 90);
        LocalDate today = LocalDate.now();
        List<Todo> due = load().stream()
                .filter(t -> !t.done() && t.due() != null)
                .filter(t -> ChronoUnit.DAYS.between(today, LocalDate.parse(t.due(), DUE)) <= window)
                .sorted(Comparator.comparing(Todo::due))
                .toList();
        if (due.isEmpty()) {
            return "未来 " + window + " 天内没有需要关注的待办。";
        }
        StringBuilder sb = new StringBuilder("未来 ").append(window).append(" 天内需要关注的待办（共 ")
                .append(due.size()).append(" 条）\n");
        for (Todo t : due) {
            long diff = ChronoUnit.DAYS.between(today, LocalDate.parse(t.due(), DUE));
            String hint = diff > 0 ? (diff + " 天后到期") : (diff == 0 ? "今天到期" : ("已逾期 " + (-diff) + " 天"));
            sb.append("- 标题=").append(t.title()).append(" 截止=").append(t.due())
                    .append("（").append(hint).append("）id=").append(t.id()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ══════════════════════════════ 存储 ══════════════════════════════

    private List<Todo> load() {
        if (repository != null) {
            RequestIdentity identity = RequestIdentityContext.require();
            return repository.findByUserId(identity.userId()).stream().map(TodoRecord::toTodo)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
        if (!Files.exists(todoFile)) {
            return new ArrayList<>();
        }
        try {
            List<Todo> todos = mapper.readValue(todoFile.toFile(), new TypeReference<List<Todo>>() { });
            return todos == null ? new ArrayList<>() : new ArrayList<>(todos);
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：待办数据文件损坏或无法读取（" + todoFile + "）。请告知用户检查数据文件。", e);
        }
    }

    private void save(List<Todo> todos) {
        if (repository != null) {
            RequestIdentity identity = RequestIdentityContext.require();
            repository.deleteByUserId(identity.userId());
            repository.saveAll(todos.stream()
                    .map(todo -> new TodoRecord(identity.userId(), identity.orgId(), todo))
                    .toList());
            return;
        }
        try {
            // 迭代 10 I10-5：工作目录可能还不存在 data/，先建父目录再写
            Path parent = todoFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(todoFile.toFile(), todos);
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：保存待办数据失败。" + e.getMessage(), e);
        }
    }
}
