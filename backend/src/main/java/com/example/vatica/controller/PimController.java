package com.example.vatica.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.CalendarTools;
import com.example.vatica.tool.TodoRecordRepository;
import com.example.vatica.tool.TodoTools;
import com.example.vatica.tool.IcsParser.CalendarEvent;

/** 迭代 14：前端直接管理个人日历和待办的结构化 API。 */
@RestController
@RequestMapping("/api/pim")
public class PimController {
    private final TodoTools todoTools;
    private final TodoRecordRepository todos;
    private final CalendarTools calendarTools;
    private final CalendarEventRecordRepository events;

    public PimController(TodoTools todoTools, TodoRecordRepository todos, CalendarTools calendarTools,
            CalendarEventRecordRepository events) {
        this.todoTools = todoTools;
        this.todos = todos;
        this.calendarTools = calendarTools;
        this.events = events;
    }

    public record TodoCreateRequest(String title, String due) { }
    public record EventCreateRequest(String summary, String start, String end, String rrule) { }
    public record EventView(Long id, String summary, String start, String end, String rrule) { }

    @GetMapping("/todos")
    public List<TodoTools.Todo> todos() {
        Long userId = RequestIdentityContext.require().userId();
        return todos.findByUserId(userId).stream().map(row -> row.toTodo()).toList();
    }

    @PostMapping("/todos")
    public List<TodoTools.Todo> addTodo(@RequestBody TodoCreateRequest request) {
        todoTools.add(request.title(), request.due());
        return todos();
    }

    @PatchMapping("/todos/{todoId}/complete")
    public List<TodoTools.Todo> completeTodo(@PathVariable String todoId) {
        todoTools.complete(todoId);
        return todos();
    }

    @DeleteMapping("/todos/{todoId}")
    @Transactional
    public void deleteTodo(@PathVariable String todoId) {
        todos.deleteByUserIdAndTodoId(RequestIdentityContext.require().userId(), todoId);
    }

    @GetMapping("/events")
    public List<EventView> events() {
        Long userId = RequestIdentityContext.require().userId();
        return events.findByUserIdOrderByStartAtAsc(userId).stream().map(this::eventView).toList();
    }

    @PostMapping("/events")
    public List<EventView> addEvent(@RequestBody EventCreateRequest request) {
        calendarTools.create(request.summary(), request.start(), request.end(), request.rrule());
        return events();
    }

    @DeleteMapping("/events/{eventId}")
    @Transactional
    public void deleteEvent(@PathVariable Long eventId) {
        RequestIdentity identity = RequestIdentityContext.require();
        CalendarEventRecord event = events.findByIdAndUserId(eventId, identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：日程不存在。"));
        events.delete(event);
    }

    @PostMapping(value = "/events/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<EventView> importEvents(@RequestPart MultipartFile file) throws IOException {
        calendarTools.importIcs(new String(file.getBytes(), StandardCharsets.UTF_8));
        return events();
    }

    @GetMapping(value = "/events/export", produces = "text/calendar")
    public ResponseEntity<byte[]> exportEvents() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename("vatica-calendar.ics").build());
        return ResponseEntity.ok().headers(headers)
                .contentType(MediaType.parseMediaType("text/calendar"))
                .body(calendarTools.exportIcs().getBytes(StandardCharsets.UTF_8));
    }

    private EventView eventView(CalendarEventRecord row) {
        CalendarEvent event = row.toEvent();
        return new EventView(row.getId(), event.summary(), event.start().toString(), event.end().toString(),
                event.rrule() == null ? null : event.rrule().toIcs().substring("RRULE:".length()));
    }
}
