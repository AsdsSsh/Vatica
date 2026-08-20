package com.example.vatica.meeting;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.IcsParser.CalendarEvent;

/**
 * 迭代 24A：会议选择和草案的受控入口。
 *
 * <p>候选由日历记录直接筛选，禁止根据标题相似度自动合并；客户端必须把用户选中的
 * {@code calendarEventId} 回传，服务端再次以当前身份读取，避免跨用户引用日程。</p>
 */
@Service
public class MeetingPreparationService {

    public record MeetingCandidate(Long eventId, String title, String start, String end) { }

    public record MeetingPreparationView(String id, String status, MeetingCandidate meeting,
            String goal, boolean knowledgeRequested, String createdAt, String updatedAt,
            String draft, String documentPath, List<String> todoIds, String rejectionReason, String error) { }

    private final CalendarEventRecordRepository eventRepository;
    private final MeetingPreparationRecordRepository preparationRepository;

    public MeetingPreparationService(CalendarEventRecordRepository eventRepository,
            MeetingPreparationRecordRepository preparationRepository) {
        this.eventRepository = eventRepository;
        this.preparationRepository = preparationRepository;
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

    /** 创建草案时只保存经用户确认的日历快照，不执行资料检索和写入动作。 */
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
                record.getDraftJson(), record.getDocumentPath(), List.of(), record.getRejectionReason(),
                record.getErrorMessage());
    }

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
