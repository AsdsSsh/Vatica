package com.example.vatica.task;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务接口（迭代 5 I5-3；迭代 5.5 返工；迭代 7 终止 + 步骤级进度事件）：
 * 创建任务（一句话）→ 审批计划/步骤 → 查询进度 → 人工返工 → 终止 → SSE 订阅进度。
 * 迭代 15 I15-1：新增任务执行轨迹查询（脱敏摘要级）。
 *
 * <p>注意（已知边界）：审批接口<b>同步</b>执行到下一个审批点或终态才返回；
 * 步骤级实时进度经 {@code GET /{id}/events}（SSE）推送，订阅即回放当前快照。
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    /** 迭代 15 I15-1：agent_trace 查询契约。 */
    public record AgentTraceView(String id, Integer stepId, String traceId, String agentId, String role,
            String skillId, String skillVersion, List<String> skillPermissions, String toolName,
            String inputSummary, String outputSummary, int outputLength, long durationMs, String status,
            String error, String createdAt) {
    }

    /** 迭代 17B：HumanAgent 写入黑板的请求契约。 */
    public record HumanNoteRequest(String content) {
    }

    private final TaskService taskService;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper mapper;
    private final AgentTraceRecordRepository traceRepository;

    public TaskController(TaskService taskService, TaskEventPublisher eventPublisher, ObjectMapper mapper,
            AgentTraceRecordRepository traceRepository) {
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
        this.mapper = mapper;
        this.traceRepository = traceRepository;
    }

    /** 一句话创建任务：Planner 拆解 → 返回计划（PENDING 待审批）；迭代 11 起携带权限快照，迭代 13 支持临时凭据。 */
    @PostMapping
    public TaskDetailDto create(@RequestBody TaskCreateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        TaskRecord record = taskService.create(body.goal(), body.permission(), body.credential(),
                body.mailCredential(), idempotencyKey, body.benchmarkCaseId());
        return detail(record);
    }

    /** 审批（计划或挂起步骤）并同步推进执行。 */
    @PostMapping("/{id}/approve")
    public TaskDetailDto approve(@PathVariable String id) {
        return detail(taskService.approve(id));
    }

    /** 人工返工（迭代 5.5）：DONE（想重做）或 NEEDS_REVISION（评测不合格）→ 重跑并同步推进。 */
    @PostMapping("/{id}/rework")
    public TaskDetailDto rework(@PathVariable String id) {
        return detail(taskService.rework(id));
    }

    /** 用户终止（迭代 7 I7-4）：PENDING/RUNNING/PENDING_APPROVAL → CANCELLED。 */
    @PostMapping("/{id}/cancel")
    public TaskDetailDto cancel(@PathVariable String id) {
        return detail(taskService.cancel(id));
    }

    /** 迭代 13 I13-6：服务重启中断后的手动继续执行（仅 recoverable 任务）。 */
    @PostMapping("/{id}/resume")
    public TaskDetailDto resume(@PathVariable String id) {
        return detail(taskService.resume(id));
    }

    /** 人工 note 立即进入任务黑板，后续波次可见；终态任务拒绝写入。 */
    @PostMapping("/{id}/notes")
    public TaskDetailDto addNote(@PathVariable String id, @RequestBody HumanNoteRequest body) {
        return detail(taskService.addHumanNote(id, body == null ? null : body.content()));
    }

    /** 步骤级进度事件（迭代 7 I7-1；迭代 16 I16-3）：支持 JWT 头和 Last-Event-ID 续传。 */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return eventPublisher.subscribe(taskService.get(id), lastEventId);
    }

    /** 单任务详情。 */
    @GetMapping("/{id}")
    public TaskDetailDto get(@PathVariable String id) {
        return detail(taskService.get(id));
    }

    /** 迭代 15 I15-1：任务执行轨迹（先按 taskId 校验归属，再返回该任务的脱敏 trace）。 */
    @GetMapping("/{id}/traces")
    public List<AgentTraceView> traces(@PathVariable String id) {
        taskService.get(id);   // 404 + 租户隔离语义与任务详情一致
        return traceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(id).stream()
                .map(TaskController::traceView)
                .toList();
    }

    private static AgentTraceView traceView(AgentTraceRecord r) {
        return new AgentTraceView(r.getId(), r.getStepId(), r.getTraceId(), r.getAgentId(), r.getRole(),
                r.getSkillId(), r.getSkillVersion(), r.getSkillPermissions(), r.getToolName(),
                r.getInputSummary(), r.getOutputSummary(), r.getOutputLength(), r.getDurationMs(),
                r.getStatus(), r.getError(), r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
    }

    /** 最近任务列表。 */
    @GetMapping
    public List<TaskSummaryDto> list() {
        return taskService.recent(20).stream().map(this::summary).toList();
    }

    private TaskSummaryDto summary(TaskRecord r) {
        return new TaskSummaryDto(r.getId(), r.getGoal(), r.getStatus().name(),
                r.getCreatedAt().toString(), r.getBenchmarkCaseId());
    }

    /** 详情与 SSE 事件负载同构（事件 = 详情 + type 字段），前端一个类型两处复用。 */
    private TaskDetailDto detail(TaskRecord r) {
        Object plan;
        try {
            plan = mapper.readValue(r.getPlanJson(), Object.class);
        } catch (Exception e) {
            plan = "（计划数据不可读）";
        }
        return new TaskDetailDto(r.getId(), r.getGoal(), r.getStatus().name(),
                r.getCreatedAt().toString(), r.getCurrentStep(), r.getPendingStepId(),
                r.getScore(), r.getVerdict() == null ? null : r.getVerdict().name(),
                r.getReworkCount(), r.getError(), plan, r.isRecoverable(), r.getExecutionAttempt(),
                r.getExecutionRuntime(), r.getLastHeartbeatAt() == null ? null : r.getLastHeartbeatAt().toString(),
                r.isRecoveryApprovalRequired(), r.getBenchmarkCaseId());
    }
}
