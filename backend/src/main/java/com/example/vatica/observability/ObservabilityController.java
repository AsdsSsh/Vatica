package com.example.vatica.observability;

import java.util.List;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.task.TaskService;

/** 迭代 21B/21C：Agent 诊断工作台的只读 API。 */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final AgentObservabilityService service;
    private final TaskService taskService;

    public ObservabilityController(AgentObservabilityService service, TaskService taskService) {
        this.service = service;
        this.taskService = taskService;
    }

    @GetMapping("/overview")
    public AgentObservabilityService.OverviewView overview(
            @RequestParam(defaultValue = "20") int limit) {
        return service.overview(RequestIdentityContext.require(), limit);
    }

    @GetMapping("/runs")
    public List<AgentObservabilityService.RunSummary> runs(
            @RequestParam(defaultValue = "50") int limit) {
        return service.runs(RequestIdentityContext.require(), limit);
    }

    /** 迭代 28A：组合筛选和服务端分页；排序字段由服务端白名单收口。 */
    @GetMapping("/spans")
    public AgentObservabilityService.RunQueryPage spans(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String spanType,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String runtime,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String modelSlotId,
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String judgeVerdict,
            @RequestParam(required = false) Long minDurationMs,
            @RequestParam(required = false) Long maxDurationMs,
            @RequestParam(required = false) Integer minJudgeScore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "startedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return service.queryRuns(RequestIdentityContext.require(), new AgentObservabilityService.SpanQuery(
                parseInstant(from, "from"), parseInstant(to, "to"), traceId, taskId, status, spanType, name,
                runtime, agentId, modelSlotId, skillId, errorCode, judgeVerdict, minDurationMs, maxDurationMs,
                minJudgeScore, page, size, sortBy, direction));
    }

    /** 迭代 28C：诊断只接受筛选事实，不接收或返回模型内部推理内容。 */
    @GetMapping("/diagnostics")
    public AgentObservabilityService.DiagnosisReport diagnostics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String spanType,
            @RequestParam(required = false) String runtime,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String modelSlotId,
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String judgeVerdict) {
        return service.diagnose(RequestIdentityContext.require(), new AgentObservabilityService.SpanQuery(
                parseInstant(from, "from"), parseInstant(to, "to"), traceId, taskId, status, spanType, null,
                runtime, agentId, modelSlotId, skillId, errorCode, judgeVerdict, null, null, null,
                0, 100, "startedAt", "asc"));
    }

    @GetMapping("/diagnostics/export")
    public ResponseEntity<byte[]> exportDiagnostics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String spanType,
            @RequestParam(required = false) String runtime,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String modelSlotId,
            @RequestParam(required = false) String skillId,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String judgeVerdict) {
        AgentObservabilityService.DiagnosisReport report = diagnostics(from, to, traceId, taskId, status, spanType,
                runtime, agentId, modelSlotId, skillId, errorCode, judgeVerdict);
        StringBuilder markdown = new StringBuilder("# Vatica Agent 事实诊断报告\n\n");
        markdown.append("- 范围：").append(report.scope()).append("\n")
                .append("- Span：").append(report.spanCount()).append("\n")
                .append("- Run：").append(report.runCount()).append("\n")
                .append("- 生成时间：").append(Instant.now()).append("\n\n")
                .append("> 本报告只包含脱敏摘要和可定位标识，不包含原始 Prompt、模型响应或思维链。\n\n")
                .append("## 事实证据\n\n");
        if (report.findings().isEmpty()) markdown.append("未发现规则命中的慢点、失败、重试或质量风险。\n");
        report.findings().forEach(finding -> markdown.append("- **").append(finding.severity()).append(" / ")
                .append(finding.kind()).append("** ").append(finding.title()).append("：")
                .append(finding.evidence()).append("（trace=").append(finding.traceId())
                .append(finding.spanId() == null ? "" : ", span=" + finding.spanId()).append("）\n"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename("vatica-diagnostics.md").build());
        return ResponseEntity.ok().headers(headers)
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(markdown.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/traces/{traceId}")
    public List<AgentObservabilityService.SpanView> trace(@PathVariable String traceId) {
        return service.trace(RequestIdentityContext.require(), traceId);
    }

    @GetMapping("/tasks/{taskId}")
    public List<AgentObservabilityService.SpanView> task(@PathVariable String taskId) {
        taskService.get(taskId); // 先走任务自身的租户校验，再读取 Span
        return service.task(RequestIdentityContext.require(), taskId);
    }

    private static Instant parseInstant(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("操作失败：" + field + " 必须是 ISO-8601 时间。", e);
        }
    }
}
