package com.example.vatica.report;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.artifact.ArtifactService;
import com.example.vatica.artifact.ArtifactView;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代 26B：周报草案和确定性模板投影。
 *
 * <p>创建时冻结 26A 事实快照；编辑只改变用户可控字段，绝不重新读取日历、待办或知识库。
 * Word/Excel 在本阶段只产生预览文本和产物索引，真实工作区写入由 26C 的受控动作执行。</p>
 */
@Service
public class WeeklyReportDraftService {

    public static final String SUBJECT_TYPE = "WEEKLY_REPORT";

    private final WeeklyReportService facts;
    private final WeeklyReportDraftRepository repository;
    private final ArtifactService artifacts;
    private final ObjectMapper mapper;

    public WeeklyReportDraftService(WeeklyReportService facts, WeeklyReportDraftRepository repository,
            ArtifactService artifacts, ObjectMapper mapper) {
        this.facts = facts;
        this.repository = repository;
        this.artifacts = artifacts;
        this.mapper = mapper;
    }

    @Transactional
    public WeeklyReportDraftView create(CreateRequest request) {
        RequestIdentity identity = RequestIdentityContext.require();
        Input input = Input.from(request);
        WeeklyReportService.WeeklyReportView snapshot = facts.collect(input.toFactsRequest());
        String id = UUID.randomUUID().toString();
        String title = input.title().isBlank() ? snapshot.from() + " 至 " + snapshot.to() + " 周报" : input.title();
        String focus = input.focus().isBlank() ? defaultFocus(snapshot) : input.focus();
        String risks = input.risks().isBlank() ? defaultRisks(snapshot) : input.risks();
        WeeklyReportDraftRecord record = new WeeklyReportDraftRecord(id, identity, title, focus,
                risks, input.nextPlan(), input.wordRequested(), input.excelRequested(), encode(snapshot));
        repository.save(record);
        return persistArtifacts(identity, view(record, snapshot));
    }

    @Transactional
    public WeeklyReportDraftView update(String id, UpdateRequest request) {
        RequestIdentity identity = RequestIdentityContext.require();
        WeeklyReportDraftRecord record = owned(id, identity);
        Input input = Input.from(request);
        WeeklyReportService.WeeklyReportView snapshot = decode(record.getFactsJson());
        String title = input.title().isBlank() ? snapshot.from() + " 至 " + snapshot.to() + " 周报" : input.title();
        record.update(title, input.focus(), input.risks(), input.nextPlan(), input.wordRequested(),
                input.excelRequested());
        repository.save(record);
        return persistArtifacts(identity, view(record, snapshot));
    }

    @Transactional(readOnly = true)
    public WeeklyReportDraftView get(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        WeeklyReportDraftRecord record = owned(id, identity);
        return withArtifacts(identity, view(record, decode(record.getFactsJson())));
    }

    @Transactional(readOnly = true)
    public List<WeeklyReportDraftView> recent() {
        RequestIdentity identity = RequestIdentityContext.require();
        return repository.findTop20ByUserIdOrderByUpdatedAtDesc(identity.userId()).stream()
                .map(record -> withArtifacts(identity, view(record, decode(record.getFactsJson())))).toList();
    }

    private WeeklyReportDraftView persistArtifacts(RequestIdentity identity, WeeklyReportDraftView value) {
        artifacts.syncWeeklyReportDraft(identity, value.id(), value.wordRequested(), value.excelRequested(),
                value.wordPreview(), value.excelPreview());
        return withArtifacts(identity, value);
    }

    private WeeklyReportDraftView withArtifacts(RequestIdentity identity, WeeklyReportDraftView value) {
        return new WeeklyReportDraftView(value.id(), value.status(), value.title(), value.focus(), value.risks(),
                value.nextPlan(), value.wordRequested(), value.excelRequested(), value.facts(), value.wordPreview(),
                value.excelPreview(), artifacts.listForSubject(identity, SUBJECT_TYPE, value.id()), value.createdAt(),
                value.updatedAt());
    }

    private WeeklyReportDraftView view(WeeklyReportDraftRecord record, WeeklyReportService.WeeklyReportView snapshot) {
        String wordPreview = record.isWordRequested() ? renderWord(record, snapshot) : null;
        String excelPreview = record.isExcelRequested() ? renderExcel(snapshot) : null;
        return new WeeklyReportDraftView(record.getId(), record.getStatus(), record.getTitle(), record.getFocus(),
                record.getRisks(), record.getNextPlan(), record.isWordRequested(), record.isExcelRequested(), snapshot,
                wordPreview, excelPreview, List.of(), instant(record.getCreatedAt()), instant(record.getUpdatedAt()));
    }

    private WeeklyReportDraftRecord owned(String id, RequestIdentity identity) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("操作失败：周报草案 ID 不能为空。");
        }
        return repository.findByIdAndUserId(id, identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：周报草案不存在或无权访问。"));
    }

    private String encode(WeeklyReportService.WeeklyReportView value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：无法保存周报事实快照。", e);
        }
    }

    private WeeklyReportService.WeeklyReportView decode(String value) {
        try {
            return mapper.readValue(value, WeeklyReportService.WeeklyReportView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：周报事实快照已损坏，请重新创建草案。", e);
        }
    }

    private static String renderWord(WeeklyReportDraftRecord record, WeeklyReportService.WeeklyReportView facts) {
        StringBuilder out = new StringBuilder("# ").append(record.getTitle()).append('\n');
        out.append("\n## 本周概览\n");
        out.append("- 统计范围：").append(facts.from()).append(" ～ ").append(facts.to()).append('\n');
        out.append("- 会议数量：").append(facts.statistics().meetingCount()).append('\n');
        out.append("- 待办完成：").append(facts.statistics().completedTodoCount()).append('\n');
        out.append("- 待办未完成：").append(facts.statistics().pendingTodoCount()).append('\n');
        out.append("- 逾期待办：").append(facts.statistics().overdueTodoCount()).append('\n');
        appendSection(out, "本周重点", record.getFocus());
        appendSection(out, "风险与阻塞", record.getRisks());
        out.append("\n## 日程事实\n");
        if (facts.calendar().isEmpty()) out.append("- 范围内没有日程\n");
        for (WeeklyReportService.CalendarFact item : facts.calendar()) {
            out.append("- ").append(item.start()).append(" · ").append(item.title()).append('\n');
        }
        out.append("\n## 待办事实\n");
        if (facts.todos().isEmpty()) out.append("- 范围内没有带截止日期的待办\n");
        for (WeeklyReportService.TodoFact item : facts.todos()) {
            out.append("- [").append(item.done() ? 'x' : ' ').append("] ").append(item.due()).append(" · ")
                    .append(item.title()).append('\n');
        }
        appendSection(out, "下周计划", record.getNextPlan());
        if (!facts.userNotes().isBlank()) appendSection(out, "用户补充", facts.userNotes());
        return out.toString().trim();
    }

    private static void appendSection(StringBuilder out, String title, String body) {
        if (body == null || body.isBlank()) return;
        out.append("\n## ").append(title).append('\n');
        for (String line : body.split("\\r?\\n")) {
            if (!line.isBlank()) out.append("- ").append(line.trim()).append('\n');
        }
    }

    private static String renderExcel(WeeklyReportService.WeeklyReportView facts) {
        StringBuilder out = new StringBuilder("指标,数值\n");
        out.append("会议数量,").append(facts.statistics().meetingCount()).append('\n');
        out.append("待办总数,").append(facts.statistics().todoCount()).append('\n');
        out.append("已完成待办,").append(facts.statistics().completedTodoCount()).append('\n');
        out.append("未完成待办,").append(facts.statistics().pendingTodoCount()).append('\n');
        out.append("逾期待办,").append(facts.statistics().overdueTodoCount()).append('\n');
        out.append("\n日期,事项,状态\n");
        for (WeeklyReportService.CalendarFact item : facts.calendar()) {
            out.append(item.start().substring(0, 10)).append(',').append(csv(item.title())).append(",日程\n");
        }
        for (WeeklyReportService.TodoFact item : facts.todos()) {
            out.append(item.due()).append(',').append(csv(item.title())).append(',')
                    .append(item.done() ? "已完成" : "未完成").append('\n');
        }
        return out.toString().trim();
    }

    private static String defaultFocus(WeeklyReportService.WeeklyReportView facts) {
        return "完成 " + facts.statistics().completedTodoCount() + " 项待办，参加 "
                + facts.statistics().meetingCount() + " 场会议。";
    }

    private static String defaultRisks(WeeklyReportService.WeeklyReportView facts) {
        if (facts.statistics().overdueTodoCount() == 0) return "当前事实快照中没有逾期待办。";
        return "有 " + facts.statistics().overdueTodoCount() + " 项待办截至范围结束日仍处于逾期未完成状态。";
    }

    private static String csv(String value) {
        if (value == null) return "";
        String clean = value.replace("\"", "\"\"");
        return clean.contains(",") ? "\"" + clean + "\"" : clean;
    }

    private static String instant(java.time.Instant value) {
        return value == null ? null : value.toString();
    }

    private record Input(String title, String focus, String risks, String nextPlan, boolean wordRequested,
            boolean excelRequested, WeeklyReportService.WeeklyReportRequest factsRequest) {
        static Input from(CreateRequest request) {
            if (request == null) throw new IllegalArgumentException("操作失败：周报草案请求不能为空。");
            return from(request.title(), request.focus(), request.risks(), request.nextPlan(), request.wordRequested(),
                    request.excelRequested(), request.from(), request.to(), request.reportType(), request.includeCalendar(),
                    request.includeTodos(), request.includeKnowledge(), request.userNotes());
        }

        static Input from(UpdateRequest request) {
            if (request == null) throw new IllegalArgumentException("操作失败：周报草案编辑请求不能为空。");
            return from(request.title(), request.focus(), request.risks(), request.nextPlan(), request.wordRequested(),
                    request.excelRequested(), null, null, null, null, null, null, null);
        }

        private static Input from(String title, String focus, String risks, String nextPlan, Boolean wordRequested,
                Boolean excelRequested, LocalDate from, LocalDate to, String reportType, Boolean includeCalendar,
                Boolean includeTodos, Boolean includeKnowledge, String userNotes) {
            String cleanTitle = clean(title, "周报标题", 240);
            String cleanFocus = clean(focus, "本周重点", 2_000);
            String cleanRisks = clean(risks, "风险与阻塞", 2_000);
            String cleanNextPlan = clean(nextPlan, "下周计划", 2_000);
            return new Input(cleanTitle, cleanFocus, cleanRisks, cleanNextPlan, wordRequested == null || wordRequested,
                    excelRequested != null && excelRequested, new WeeklyReportService.WeeklyReportRequest(from, to,
                            reportType, includeCalendar, includeTodos, includeKnowledge, userNotes));
        }

        private static String clean(String value, String label, int max) {
            String result = value == null ? "" : value.trim();
            if (result.length() > max) throw new IllegalArgumentException("操作失败：" + label + "不能超过 " + max + " 个字符。");
            return result;
        }

        WeeklyReportService.WeeklyReportRequest toFactsRequest() { return factsRequest; }
    }

    public record CreateRequest(LocalDate from, LocalDate to, String reportType, Boolean includeCalendar,
            Boolean includeTodos, Boolean includeKnowledge, String userNotes, String title, String focus, String risks,
            String nextPlan, Boolean wordRequested, Boolean excelRequested) { }

    public record UpdateRequest(String title, String focus, String risks, String nextPlan, Boolean wordRequested,
            Boolean excelRequested) { }

    public record WeeklyReportDraftView(String id, String status, String title, String focus, String risks,
            String nextPlan, boolean wordRequested, boolean excelRequested, WeeklyReportService.WeeklyReportView facts,
            String wordPreview, String excelPreview, List<ArtifactView> artifacts, String createdAt, String updatedAt) { }
}
