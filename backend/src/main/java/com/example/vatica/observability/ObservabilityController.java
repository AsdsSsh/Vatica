package com.example.vatica.observability;

import java.util.List;

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

    @GetMapping("/traces/{traceId}")
    public List<AgentObservabilityService.SpanView> trace(@PathVariable String traceId) {
        return service.trace(RequestIdentityContext.require(), traceId);
    }

    @GetMapping("/tasks/{taskId}")
    public List<AgentObservabilityService.SpanView> task(@PathVariable String taskId) {
        taskService.get(taskId); // 先走任务自身的租户校验，再读取 Span
        return service.task(RequestIdentityContext.require(), taskId);
    }
}
