package com.example.vatica.meeting;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.knowledge.KnowledgeBaseService;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.IcsParser.CalendarEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代 24A：会议选择和草案的受控入口。
 *
 * <p>候选由日历记录直接筛选，禁止根据标题相似度自动合并；客户端必须把用户选中的
 * {@code calendarEventId} 回传，服务端再次以当前身份读取，避免跨用户引用日程。</p>
 */
@Service
public class MeetingPreparationService {

    public record MeetingCandidate(Long eventId, String title, String start, String end) { }

    /** 资料和用户输入都作为独立来源呈现，建议本身不会伪装成日历事实。 */
    public record Evidence(String type, String label, String sourceId, String detail) { }

    public record KnowledgeCitation(String citationId, String documentName, String sourcePath,
            String heading, int startOffset, int endOffset, double score, String quote) { }

    /** 待办差异只是预览；24C 批准后才把它们写入用户待办。 */
    public record TodoDraft(String title, String due) { }

    public record MeetingPreparationDraft(MeetingCandidate meeting, String goal, List<Evidence> evidence,
            String knowledgeStatus, String knowledgeMessage, List<KnowledgeCitation> citations,
            List<String> agendaSuggestions, List<String> openQuestions, List<TodoDraft> todoDrafts,
            String documentPreview) { }

    public record MeetingPreparationView(String id, String status, MeetingCandidate meeting,
            String goal, boolean knowledgeRequested, String createdAt, String updatedAt,
            MeetingPreparationDraft draft, String documentPath, List<String> todoIds, String rejectionReason,
            String error) { }

    private final CalendarEventRecordRepository eventRepository;
    private final MeetingPreparationRecordRepository preparationRepository;
    private final KnowledgeBaseService knowledge;
    private final ObjectMapper mapper;

    public MeetingPreparationService(CalendarEventRecordRepository eventRepository,
            MeetingPreparationRecordRepository preparationRepository, KnowledgeBaseService knowledge,
            ObjectMapper mapper) {
        this.eventRepository = eventRepository;
        this.preparationRepository = preparationRepository;
        this.knowledge = knowledge;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<MeetingCandidate> candidates(String rawFrom, String rawTo, String rawTopic) {
        RequestIdentity identity = RequestIdentityContext.require();
        LocalDate from = parseDate(rawFrom, "开始日期");
        LocalDate to = parseDate(rawTo, "结束日期");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("操作失败：结束日期不能早于开始日期。");
        }
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime endExclusive = to.plusDays(1).atStartOfDay();
        String topic = rawTopic == null ? "" : rawTopic.trim().toLowerCase(Locale.ROOT);
        return eventRepository.findByUserIdOrderByStartAtAsc(identity.userId()).stream()
                .map(this::candidate)
                .filter(candidate -> intersects(candidate, start, endExclusive))
                .filter(candidate -> topic.isBlank() || candidate.title().toLowerCase(Locale.ROOT).contains(topic))
                .toList();
    }

    /** 创建草案时只保存经用户确认的日历快照，不执行文件或待办写入动作。 */
    @Transactional
    public MeetingPreparationView create(Long rawEventId, String rawGoal, Boolean rawKnowledgeRequested) {
        if (rawEventId == null) {
            throw new IllegalArgumentException("操作失败：请先选择一场会议。");
        }
        RequestIdentity identity = RequestIdentityContext.require();
        CalendarEventRecord event = eventRepository.findByIdAndUserId(rawEventId, identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：会议不存在或无权使用。"));
        CalendarEvent calendar = event.toEvent();
        String goal = normalizeGoal(rawGoal);
        MeetingPreparationRecord record = new MeetingPreparationRecord(UUID.randomUUID().toString(), identity,
                event.getId(), calendar.summary(), calendar.start(), calendar.end(), goal,
                Boolean.TRUE.equals(rawKnowledgeRequested));
        record.replaceDraft(goal, Boolean.TRUE.equals(rawKnowledgeRequested), encode(buildDraft(record)));
        return view(preparationRepository.save(record));
    }

    /** 用户调整目标或资料选项后，只重新生成预览；仍不产生任何外部副作用。 */
    @Transactional
    public MeetingPreparationView refreshDraft(String id, String rawGoal, Boolean rawKnowledgeRequested) {
        MeetingPreparationRecord record = owned(id);
        String goal = normalizeGoal(rawGoal);
        boolean knowledgeRequested = Boolean.TRUE.equals(rawKnowledgeRequested);
        record.replaceDraft(goal, knowledgeRequested, encode(buildDraft(record)));
        return view(preparationRepository.save(record));
    }

    @Transactional(readOnly = true)
    public MeetingPreparationView get(String id) {
        return view(owned(id));
    }

    @Transactional(readOnly = true)
    public List<MeetingPreparationView> recent() {
        RequestIdentity identity = RequestIdentityContext.require();
        return preparationRepository.findTop20ByUserIdOrderByCreatedAtDesc(identity.userId()).stream()
                .map(this::view).toList();
    }

    private MeetingPreparationRecord owned(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("操作失败：会议准备 ID 不能为空。");
        }
        return preparationRepository.findByIdAndUserId(id, RequestIdentityContext.require().userId())
                .orElseThrow(() -> new MeetingPreparationNotFoundException(id));
    }

    private MeetingCandidate candidate(CalendarEventRecord record) {
        CalendarEvent event = record.toEvent();
        return new MeetingCandidate(record.getId(), event.summary(), event.start().toString(), event.end().toString());
    }

    private static boolean intersects(MeetingCandidate candidate, LocalDateTime start, LocalDateTime endExclusive) {
        LocalDateTime meetingStart = LocalDateTime.parse(candidate.start());
        LocalDateTime meetingEnd = LocalDateTime.parse(candidate.end());
        return meetingStart.isBefore(endExclusive) && meetingEnd.isAfter(start);
    }

    private MeetingPreparationView view(MeetingPreparationRecord record) {
        MeetingCandidate meeting = new MeetingCandidate(record.getCalendarEventId(), record.getMeetingTitle(),
                record.getMeetingStartAt().toString(), record.getMeetingEndAt().toString());
        return new MeetingPreparationView(record.getId(), record.getStatus().name(), meeting, record.getUserGoal(),
                record.isKnowledgeRequested(), instant(record.getCreatedAt()), instant(record.getUpdatedAt()),
                decode(record.getDraftJson()), record.getDocumentPath(), List.of(), record.getRejectionReason(),
                record.getErrorMessage());
    }

    /** 只根据明确来源生成结构；议程和问题都是固定的准备建议，不会被标为会议事实。 */
    private MeetingPreparationDraft buildDraft(MeetingPreparationRecord record) {
        MeetingCandidate meeting = new MeetingCandidate(record.getCalendarEventId(), record.getMeetingTitle(),
                record.getMeetingStartAt().toString(), record.getMeetingEndAt().toString());
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("CALENDAR_EVENT", "日历会议", "calendar:" + record.getCalendarEventId(),
                record.getMeetingTitle() + " · " + formatTime(record.getMeetingStartAt()) + " 至 "
                        + formatTime(record.getMeetingEndAt())));
        if (record.getUserGoal() != null) {
            evidence.add(new Evidence("USER_INPUT", "用户准备目标", "user-goal", record.getUserGoal()));
        }

        KnowledgeDraft knowledgeDraft = collectKnowledge(record);
        String due = record.getMeetingStartAt().toLocalDate().toString();
        List<TodoDraft> todos = List.of(
                new TodoDraft("整理“" + record.getMeetingTitle() + "”的会议目标与已有材料", due),
                new TodoDraft("确认“" + record.getMeetingTitle() + "”的待决事项与负责人", due));
        List<String> agenda = List.of(
                "明确本次会议需要形成的决策、结论或下一步。",
                "核对现有进展、风险与阻塞，区分已知事实和待确认信息。",
                "确认负责人、截止时间和会后跟进方式。");
        List<String> questions = List.of(
                "当前日历记录未提供参会者、地点和正式议程；如这些信息会影响准备，请在批准前补充。",
                record.getUserGoal() == null
                        ? "尚未提供特别准备目标；可在草案中补充本次会议希望达成的结果。"
                        : "准备目标将围绕“" + record.getUserGoal() + "”执行，请确认范围是否完整。");
        return new MeetingPreparationDraft(meeting, record.getUserGoal(), List.copyOf(evidence),
                knowledgeDraft.status(), knowledgeDraft.message(), knowledgeDraft.citations(), agenda, questions, todos,
                renderDocumentPreview(meeting, record.getUserGoal(), evidence, knowledgeDraft, agenda, questions, todos));
    }

    private KnowledgeDraft collectKnowledge(MeetingPreparationRecord record) {
        if (!record.isKnowledgeRequested()) {
            return new KnowledgeDraft("NOT_REQUESTED", "未选择检索授权资料；草案仅基于日历和用户输入。", List.of());
        }
        try {
            String query = record.getMeetingTitle() + (record.getUserGoal() == null ? "" : " " + record.getUserGoal());
            KnowledgeBaseService.SearchResult result = knowledge.search(query, 3);
            List<KnowledgeCitation> citations = result.citations().stream()
                    .map(citation -> new KnowledgeCitation(citation.citationId(), citation.documentName(),
                            citation.sourcePath(), citation.heading(), citation.startOffset(), citation.endOffset(),
                            citation.score(), citation.quote()))
                    .toList();
            String message = citations.isEmpty()
                    ? "已检索授权资料，但没有命中可引用片段；草案未补充资料结论。"
                    : "已附加 " + citations.size() + " 条授权资料引用；引用仅供核对，不自动推导会议事实。";
            return new KnowledgeDraft("READY", message, citations);
        } catch (RuntimeException ignored) {
            // pgvector、配置或临时连接失败都只能降级，不能将技术细节或内部地址返回给用户。
            return new KnowledgeDraft("DEGRADED", "授权资料检索当前不可用；已仅基于日历和用户输入生成草案。", List.of());
        }
    }

    private String encode(MeetingPreparationDraft draft) {
        try {
            return mapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：会议准备草案序列化失败。", e);
        }
    }

    private MeetingPreparationDraft decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, MeetingPreparationDraft.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：会议准备草案不可读取。", e);
        }
    }

    private static String renderDocumentPreview(MeetingCandidate meeting, String goal, List<Evidence> evidence,
            KnowledgeDraft knowledge, List<String> agenda, List<String> questions, List<TodoDraft> todos) {
        StringBuilder text = new StringBuilder("# ").append(meeting.title()).append(" 会议准备\n\n")
                .append("## 会议事实\n")
                .append("- 标题：").append(meeting.title()).append("\n")
                .append("- 时间：").append(meeting.start().replace('T', ' ')).append(" 至 ")
                .append(meeting.end().replace('T', ' ')).append("\n\n")
                .append("## 准备目标\n")
                .append(goal == null ? "- 未补充；请在批准前确认本次会议希望达成的结果。\n\n" : "- " + goal + "\n\n")
                .append("## 建议议程\n");
        agenda.forEach(item -> text.append("- ").append(item).append('\n'));
        text.append("\n## 待确认事项\n");
        questions.forEach(item -> text.append("- ").append(item).append('\n'));
        text.append("\n## 待办草案\n");
        todos.forEach(todo -> text.append("- [ ] ").append(todo.title()).append("（截止 ")
                .append(todo.due()).append("）\n"));
        text.append("\n## 资料状态\n- ").append(knowledge.message()).append('\n');
        if (!knowledge.citations().isEmpty()) {
            text.append("\n## 授权资料引用\n");
            knowledge.citations().forEach(citation -> text.append("- [").append(citation.citationId()).append("] ")
                    .append(citation.documentName()).append(" · ").append(citation.sourcePath()).append("\n"));
        }
        text.append("\n## 来源\n");
        evidence.forEach(item -> text.append("- ").append(item.label()).append("：").append(item.detail()).append('\n'));
        return text.toString();
    }

    private static String formatTime(LocalDateTime time) {
        return time.toString().replace('T', ' ');
    }

    private record KnowledgeDraft(String status, String message, List<KnowledgeCitation> citations) { }

    private static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("操作失败：" + label + "不能为空。");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("操作失败：" + label + "格式应为 yyyy-MM-dd。", e);
        }
    }

    private static String normalizeGoal(String value) {
        if (value == null || value.isBlank()) return null;
        String goal = value.trim().replaceAll("\\s+", " ");
        if (goal.length() > 2_000) {
            throw new IllegalArgumentException("操作失败：准备目标不能超过 2000 个字符。");
        }
        return goal;
    }

    private static String instant(java.time.Instant value) {
        return value == null ? null : value.toString();
    }
}
