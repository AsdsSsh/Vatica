package com.example.vatica.meeting;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.vatica.action.ActionPlanView;
import com.example.vatica.action.ActionExecutionService;
import com.example.vatica.artifact.ArtifactService;
import com.example.vatica.artifact.ArtifactView;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.knowledge.KnowledgeBaseService;
import com.example.vatica.permission.PermissionPolicyService;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.IcsParser.CalendarEvent;
import com.example.vatica.tool.TodoRecord;
import com.example.vatica.tool.TodoRecordRepository;
import com.example.vatica.tool.TodoTools;
import com.example.vatica.workspace.WorkspaceStore;
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
            String heading, int startOffset, int endOffset, double score, String quote, String sourceLocation,
            String snippet, int documentVersion, String indexVersion, String accessScope) { }

    /** 待办差异只是预览；24C 批准后才把它们写入用户待办。 */
    public record TodoDraft(String title, String due) { }

    public record MeetingPreparationDraft(MeetingCandidate meeting, String goal, List<Evidence> evidence,
            String knowledgeStatus, String knowledgeMessage, List<KnowledgeCitation> citations,
            List<String> agendaSuggestions, List<String> openQuestions, List<TodoDraft> todoDrafts,
            String documentPreview) { }

    public record MeetingPreparationView(String id, String status, MeetingCandidate meeting,
            String goal, boolean knowledgeRequested, String createdAt, String updatedAt,
            MeetingPreparationDraft draft, String documentPath, List<String> todoIds, String rejectionReason,
            String error, ActionPlanView actionPlan, List<ArtifactView> artifacts) {

        /** 24A/24B 测试与外部调用兼容：没有动作计划时仍可构造旧版视图。 */
        public MeetingPreparationView(String id, String status, MeetingCandidate meeting, String goal,
                boolean knowledgeRequested, String createdAt, String updatedAt, MeetingPreparationDraft draft,
                String documentPath, List<String> todoIds, String rejectionReason, String error) {
            this(id, status, meeting, goal, knowledgeRequested, createdAt, updatedAt, draft, documentPath, todoIds,
                    rejectionReason, error, null);
        }

        public MeetingPreparationView(String id, String status, MeetingCandidate meeting, String goal,
                boolean knowledgeRequested, String createdAt, String updatedAt, MeetingPreparationDraft draft,
                String documentPath, List<String> todoIds, String rejectionReason, String error,
                ActionPlanView actionPlan) {
            this(id, status, meeting, goal, knowledgeRequested, createdAt, updatedAt, draft, documentPath, todoIds,
                    rejectionReason, error, actionPlan, List.of());
        }
    }

    private final CalendarEventRecordRepository eventRepository;
    private final MeetingPreparationRecordRepository preparationRepository;
    private final KnowledgeBaseService knowledge;
    private final ObjectMapper mapper;
    private final WorkspaceStore workspace;
    private final TodoRecordRepository todoRepository;
    private final ActionExecutionService actionExecutions;
    private final ArtifactService artifacts;
    private final PermissionPolicyService permissionPolicy;

    @Autowired
    public MeetingPreparationService(CalendarEventRecordRepository eventRepository,
            MeetingPreparationRecordRepository preparationRepository, KnowledgeBaseService knowledge,
            ObjectMapper mapper, WorkspaceStore workspace, TodoRecordRepository todoRepository,
            ActionExecutionService actionExecutions, ArtifactService artifacts, PermissionPolicyService permissionPolicy) {
        this.eventRepository = eventRepository;
        this.preparationRepository = preparationRepository;
        this.knowledge = knowledge;
        this.mapper = mapper;
        this.workspace = workspace;
        this.todoRepository = todoRepository;
        this.actionExecutions = actionExecutions;
        this.artifacts = artifacts;
        this.permissionPolicy = permissionPolicy;
    }

    /** 24C 单元测试与外部构造兼容；生产装配使用带动作仓库的完整构造器。 */
    public MeetingPreparationService(CalendarEventRecordRepository eventRepository,
            MeetingPreparationRecordRepository preparationRepository, KnowledgeBaseService knowledge,
            ObjectMapper mapper, WorkspaceStore workspace, TodoRecordRepository todoRepository) {
        this(eventRepository, preparationRepository, knowledge, mapper, workspace, todoRepository, null, null, null);
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
        MeetingPreparationDraft draft = buildDraft(record);
        record.replaceDraft(goal, Boolean.TRUE.equals(rawKnowledgeRequested), encode(draft));
        MeetingPreparationRecord saved = preparationRepository.save(record);
        syncArtifacts(identity, saved, draft);
        return view(saved);
    }

    /** 用户调整目标或资料选项后，只重新生成预览；仍不产生任何外部副作用。 */
    @Transactional
    public MeetingPreparationView refreshDraft(String id, String rawGoal, Boolean rawKnowledgeRequested) {
        MeetingPreparationRecord record = owned(id);
        String goal = normalizeGoal(rawGoal);
        boolean knowledgeRequested = Boolean.TRUE.equals(rawKnowledgeRequested);
        MeetingPreparationDraft draft = buildDraft(record);
        record.replaceDraft(goal, knowledgeRequested, encode(draft));
        MeetingPreparationRecord saved = preparationRepository.save(record);
        syncArtifacts(RequestIdentityContext.require(), saved, draft);
        return view(saved);
    }

    /** 24C/25B：批准并执行动作计划；行级锁和稳定动作记录共同保证恢复不重复写入。 */
    @Transactional
    public MeetingPreparationView approve(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        MeetingPreparationRecord record = preparationRepository.findForApproval(id, identity.userId())
                .orElseThrow(() -> new MeetingPreparationNotFoundException(id));
        if (record.getStatus() == MeetingPreparationStatus.APPLIED) {
            return view(record);
        }
        if (record.getStatus() != MeetingPreparationStatus.DRAFT) {
            throw new IllegalStateException("操作失败：只有待批准的会议准备可以写入。");
        }
        MeetingPreparationDraft draft = decode(record.getDraftJson());
        if (draft == null) {
            throw new IllegalStateException("操作失败：会议准备草案缺失，无法执行写入。");
        }
        if (actionExecutions == null) {
            return legacyApprove(identity, record, draft);
        }
        ActionPlanView plan = plan(record, draft);
        actionExecutions.approve(plan);
        return executeApproved(identity, record, draft, plan);
    }

    /** 兼容 24C 纯场景单测的旧执行路径；生产请求始终走 25B 动作记录。 */
    private MeetingPreparationView legacyApprove(RequestIdentity identity, MeetingPreparationRecord record,
            MeetingPreparationDraft draft) {
        String documentPath = "meeting-preparation-" + record.getId() + ".md";
        try {
            workspace.write(identity, documentPath,
                    new ByteArrayInputStream(draft.documentPreview().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            record.markFailed(null, "准备文档写入失败，请检查工作区后重试。");
            return view(preparationRepository.save(record));
        }
        try {
            List<String> todoIds = new ArrayList<>();
            for (TodoDraft todo : draft.todoDrafts()) {
                String todoId = UUID.randomUUID().toString().substring(0, 8);
                TodoTools.Todo created = new TodoTools.Todo(todoId, todo.title(), todo.due(), false,
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                todoRepository.save(new TodoRecord(identity.userId(), identity.orgId(), created));
                todoIds.add(todoId);
            }
            record.markApplied(documentPath, encodeTodoIds(todoIds));
            return view(preparationRepository.save(record));
        } catch (RuntimeException e) {
            record.markFailed(documentPath, "待办写入未完成；请检查本次会议准备的产物与待办后重新创建草案。");
            return view(preparationRepository.save(record));
        }
    }

    /** 25B：只恢复失败或中断动作；已成功动作由 claim() 跳过，父记录重新进入执行阶段。 */
    @Transactional
    public MeetingPreparationView retry(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        MeetingPreparationRecord record = preparationRepository.findForApproval(id, identity.userId())
                .orElseThrow(() -> new MeetingPreparationNotFoundException(id));
        if (record.getStatus() != MeetingPreparationStatus.FAILED) {
            throw new IllegalStateException("操作失败：只有失败的会议准备可以重试。");
        }
        MeetingPreparationDraft draft = decode(record.getDraftJson());
        if (draft == null) {
            throw new IllegalStateException("操作失败：会议准备草案缺失，无法恢复。");
        }
        ActionPlanView plan = plan(record, draft);
        actionExecutions.requeueRecoverable(plan);
        record.resumeForRetry();
        return executeApproved(identity, record, draft, plan);
    }

    /** 25B：失败后取消未开始动作；已经成功或失败的动作保持真实状态。 */
    @Transactional
    public MeetingPreparationView cancel(String id) {
        RequestIdentity identity = RequestIdentityContext.require();
        MeetingPreparationRecord record = preparationRepository.findForApproval(id, identity.userId())
                .orElseThrow(() -> new MeetingPreparationNotFoundException(id));
        if (record.getStatus() != MeetingPreparationStatus.FAILED) {
            throw new IllegalStateException("操作失败：只有失败的会议准备可以取消后续恢复。");
        }
        MeetingPreparationDraft draft = decode(record.getDraftJson());
        if (draft == null) {
            throw new IllegalStateException("操作失败：会议准备草案缺失，无法取消恢复。");
        }
        actionExecutions.cancelNotStarted(plan(record, draft));
        record.cancelRecovery("已取消未开始的恢复动作；已完成动作和失败原因仍保留。");
        return persisted(record, draft, plan(record, draft));
    }

    private MeetingPreparationView executeApproved(RequestIdentity identity, MeetingPreparationRecord record,
            MeetingPreparationDraft draft, ActionPlanView plan) {
        String documentPath = "meeting-preparation-" + record.getId() + ".md";
        if (actionExecutions.claim(plan, "document") == ActionExecutionService.Claim.EXECUTE) {
            try {
                ensureWorkspaceWriteStillAllowed();
                workspace.write(identity, documentPath,
                        new ByteArrayInputStream(draft.documentPreview().getBytes(StandardCharsets.UTF_8)));
                actionExecutions.succeed(plan, "document", documentPath);
            } catch (IOException | IllegalStateException e) {
                String message = e.getMessage() == null ? "工作区写入被拒绝。" : e.getMessage();
                actionExecutions.fail(plan, "document", "WORKSPACE_WRITE_NOT_ALLOWED", message);
                record.markFailed(null, "准备文档写入失败：" + message);
                return persisted(record, draft, plan);
            }
        }

        List<String> todoIds = new ArrayList<>();
        for (int index = 0; index < draft.todoDrafts().size(); index++) {
            String actionId = "todo-" + (index + 1);
            String todoId = stableTodoId(plan, actionId);
            try {
                if (actionExecutions.claim(plan, actionId) == ActionExecutionService.Claim.EXECUTE) {
                    TodoDraft todo = draft.todoDrafts().get(index);
                    if (todoRepository.findByUserIdAndTodoId(identity.userId(), todoId).isEmpty()) {
                        TodoTools.Todo created = new TodoTools.Todo(todoId, todo.title(), todo.due(), false,
                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                        todoRepository.save(new TodoRecord(identity.userId(), identity.orgId(), created));
                    }
                    actionExecutions.succeed(plan, actionId, todoId);
                }
                todoIds.add(todoId);
            } catch (RuntimeException e) {
                actionExecutions.fail(plan, actionId, "TODO_WRITE_FAILED", "待办写入失败，请重试该动作。");
                record.markFailed(documentPath, "待办写入未完成；已完成动作保留，失败动作可单独重试。");
                return persisted(record, draft, plan);
            }
        }
        record.markApplied(documentPath, encodeTodoIds(todoIds));
        return persisted(record, draft, plan);
    }

    /** 25D：批准不是永久权限；真正写入前重新读取服务端权限，覆盖用户刚刚回收写权限的情况。 */
    private void ensureWorkspaceWriteStillAllowed() {
        if (permissionPolicy == null) return;
        boolean writable = permissionPolicy.current().workspaceRoots().stream().anyMatch(root -> root.write());
        if (!writable) {
            throw new IllegalStateException("当前工作区写权限已被回收，请重新授权后重试。");
        }
    }

    private ActionPlanView plan(MeetingPreparationRecord record, MeetingPreparationDraft draft) {
        return ActionPlanView.meetingPreparation(record.getId(), record.getMeetingTitle(), record.getDocumentPath(),
                draft.todoDrafts().stream().map(TodoDraft::title).toList(), decodeTodoIds(record.getTodoIdsJson()),
                record.getStatus().name());
    }

    private String stableTodoId(ActionPlanView plan, String actionId) {
        String key = plan.actions().stream().filter(action -> action.id().equals(actionId))
                .map(ActionPlanView.ActionItemView::idempotencyKey).findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：待办动作幂等键缺失。"));
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 拒绝草案只记录用户反馈，不触发工作区或待办写入。 */
    @Transactional
    public MeetingPreparationView reject(String id, String rawReason) {
        MeetingPreparationRecord record = owned(id);
        record.reject(normalizeReason(rawReason));
        MeetingPreparationDraft draft = decode(record.getDraftJson());
        MeetingPreparationRecord saved = preparationRepository.save(record);
        if (draft != null) syncArtifacts(RequestIdentityContext.require(), saved, draft);
        return view(saved);
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

    private String encodeTodoIds(List<String> ids) {
        try {
            return mapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：会议准备待办结果序列化失败。", e);
        }
    }

    private List<String> decodeTodoIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("操作失败：会议准备待办结果不可读取。", e);
        }
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
        MeetingPreparationDraft draft = decode(record.getDraftJson());
        List<String> todoTitles = draft == null ? List.of()
                : draft.todoDrafts().stream().map(TodoDraft::title).toList();
        List<String> todoIds = decodeTodoIds(record.getTodoIdsJson());
        ActionPlanView actionPlan = ActionPlanView.meetingPreparation(record.getId(), record.getMeetingTitle(),
                record.getDocumentPath(), todoTitles, todoIds, record.getStatus().name());
        if (actionExecutions != null) actionPlan = actionExecutions.decorate(actionPlan);
        List<ArtifactView> artifactViews = artifacts == null ? List.of()
                : artifacts.list(ArtifactService.MEETING_PREPARATION, record.getId());
        return new MeetingPreparationView(record.getId(), record.getStatus().name(), meeting, record.getUserGoal(),
                record.isKnowledgeRequested(), instant(record.getCreatedAt()), instant(record.getUpdatedAt()),
                draft, record.getDocumentPath(), todoIds, record.getRejectionReason(), record.getErrorMessage(), actionPlan,
                artifactViews);
    }

    private MeetingPreparationView persisted(MeetingPreparationRecord record, MeetingPreparationDraft draft,
            ActionPlanView plan) {
        MeetingPreparationRecord saved = preparationRepository.save(record);
        syncArtifacts(RequestIdentityContext.require(), saved, draft);
        return view(saved);
    }

    private void syncArtifacts(RequestIdentity identity, MeetingPreparationRecord record,
            MeetingPreparationDraft draft) {
        if (artifacts == null) return;
        ActionPlanView plan = plan(record, draft);
        if (actionExecutions != null) plan = actionExecutions.decorate(plan);
        artifacts.syncMeetingPreparation(identity, record.getId(), plan, record.getStatus().name(), record.getErrorMessage());
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
                            citation.score(), citation.quote(), citation.sourceLocation(), citation.snippet(),
                            citation.documentVersion(), citation.indexVersion(), citation.accessScope()))
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

    private static String normalizeReason(String value) {
        if (value == null || value.isBlank()) return null;
        String reason = value.trim().replaceAll("\\s+", " ");
        if (reason.length() > 1_000) {
            throw new IllegalArgumentException("操作失败：拒绝原因不能超过 1000 个字符。");
        }
        return reason;
    }

    private static String instant(java.time.Instant value) {
        return value == null ? null : value.toString();
    }
}
