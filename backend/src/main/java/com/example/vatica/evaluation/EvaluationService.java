package com.example.vatica.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.task.TaskRecord;
import com.example.vatica.task.TaskRecordRepository;
import com.example.vatica.task.TaskStatus;
import com.example.vatica.task.TaskVerdict;
import com.example.vatica.trace.AgentTraceRecord;
import com.example.vatica.trace.AgentTraceRecordRepository;
import com.example.vatica.usage.UsageRecord;
import com.example.vatica.usage.UsageRecordRepository;

/** 迭代 18C：按固定用例和真实执行运行时生成可重复的质量/成本门禁报告。 */
@Service
public class EvaluationService {

    private static final List<String> RUNTIMES = List.of("agentscope");

    public enum GateStatus {
        PENDING,
        PASS,
        FAIL
    }

    public record Thresholds(int minSamplesPerCase, double minPassRate,
            double minAverageScore, double maxFailedToolRate) {
    }

    public record CaseRuntimeResult(String caseId, String title, String runtime,
            int taskCount, int terminalSamples, int passedTasks, int failedTasks, int cancelledTasks,
            Double passRate, Double averageScore, Double averageDurationMs,
            int inputTokens, int outputTokens, int totalTokens, double costEstimate,
            int toolCalls, int failedToolCalls) {
    }

    public record RuntimeGate(String runtime, GateStatus status, int coveredCases, int totalCases,
            int terminalSamples, Double passRate, Double averageScore, Double failedToolRate,
            int totalTokens, double costEstimate, List<String> reasons) {
        public RuntimeGate {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record EvaluationReport(String generatedAt, Thresholds thresholds,
            List<CaseRuntimeResult> results, List<RuntimeGate> gates) {
    }

    private final BenchmarkCatalog catalog;
    private final EvaluationProperties properties;
    private final TaskRecordRepository taskRepository;
    private final UsageRecordRepository usageRepository;
    private final AgentTraceRecordRepository traceRepository;

    public EvaluationService(BenchmarkCatalog catalog, EvaluationProperties properties,
            TaskRecordRepository taskRepository, UsageRecordRepository usageRepository,
            AgentTraceRecordRepository traceRepository) {
        this.catalog = catalog;
        this.properties = properties;
        this.taskRepository = taskRepository;
        this.usageRepository = usageRepository;
        this.traceRepository = traceRepository;
    }

    public EvaluationReport report(RequestIdentity identity) {
        Map<String, UsageAccumulator> usageByTask = usageByTask(identity.userId());
        Map<String, ToolAccumulator> toolsByTask = toolsByTask(identity.userId());
        Map<String, CaseAccumulator> values = initializedCases();

        for (TaskRecord task : taskRepository.findByUserId(identity.userId())) {
            if (task.getBenchmarkCaseId() == null || task.getExecutionRuntime() == null) {
                continue;
            }
            CaseAccumulator accumulator = values.get(key(task.getBenchmarkCaseId(), task.getExecutionRuntime()));
            if (accumulator == null) {
                continue;
            }
            accumulator.add(task, usageByTask.get(task.getId()), toolsByTask.get(task.getId()));
        }

        List<CaseRuntimeResult> results = values.values().stream().map(CaseAccumulator::view).toList();
        List<RuntimeGate> gates = RUNTIMES.stream()
                .map(runtime -> gate(runtime, values))
                .toList();
        Thresholds thresholds = new Thresholds(properties.minSamplesPerCase(), properties.minPassRate(),
                properties.minAverageScore(), properties.maxFailedToolRate());
        return new EvaluationReport(Instant.now().toString(), thresholds, results, gates);
    }

    private Map<String, CaseAccumulator> initializedCases() {
        Map<String, CaseAccumulator> values = new LinkedHashMap<>();
        for (String runtime : RUNTIMES) {
            for (BenchmarkCase item : catalog.cases()) {
                values.put(key(item.id(), runtime), new CaseAccumulator(item, runtime));
            }
        }
        return values;
    }

    private Map<String, UsageAccumulator> usageByTask(Long userId) {
        Map<String, UsageAccumulator> values = new java.util.HashMap<>();
        for (UsageRecord row : usageRepository.findTaskUsageByUserId(userId)) {
            if (row.getTaskId() == null) {
                continue;
            }
            UsageAccumulator accumulator = values.computeIfAbsent(row.getTaskId(), ignored -> new UsageAccumulator());
            accumulator.inputTokens += row.getInputTokens();
            accumulator.outputTokens += row.getOutputTokens();
            accumulator.totalTokens += row.getTotalTokens();
            accumulator.costEstimate += row.getCostEstimate();
        }
        return values;
    }

    private Map<String, ToolAccumulator> toolsByTask(Long userId) {
        Map<String, ToolAccumulator> values = new java.util.HashMap<>();
        for (AgentTraceRecord row : traceRepository.findTaskTracesByUserId(userId)) {
            if (row.getTaskId() == null) {
                continue;
            }
            ToolAccumulator accumulator = values.computeIfAbsent(row.getTaskId(), ignored -> new ToolAccumulator());
            accumulator.calls++;
            if (AgentTraceRecord.STATUS_FAILED.equals(row.getStatus())) {
                accumulator.failed++;
            }
        }
        return values;
    }

    private RuntimeGate gate(String runtime, Map<String, CaseAccumulator> values) {
        List<CaseAccumulator> cases = values.values().stream()
                .filter(item -> item.runtime.equals(runtime))
                .toList();
        int covered = (int) cases.stream()
                .filter(item -> item.terminalSamples >= properties.minSamplesPerCase())
                .count();
        int samples = cases.stream().mapToInt(item -> item.terminalSamples).sum();
        int passed = cases.stream().mapToInt(item -> item.passed).sum();
        int scored = cases.stream().mapToInt(item -> item.scored).sum();
        int scoreTotal = cases.stream().mapToInt(item -> item.scoreTotal).sum();
        int tools = cases.stream().mapToInt(item -> item.toolCalls).sum();
        int failedTools = cases.stream().mapToInt(item -> item.failedToolCalls).sum();
        int tokens = cases.stream().mapToInt(item -> item.totalTokens).sum();
        double cost = cases.stream().mapToDouble(item -> item.costEstimate).sum();
        Double passRate = samples == 0 ? null : (double) passed / samples;
        Double averageScore = scored == 0 ? null : (double) scoreTotal / scored;
        Double failedToolRate = tools == 0 ? 0.0 : (double) failedTools / tools;

        List<String> reasons = new ArrayList<>();
        if (covered < cases.size()) {
            cases.stream()
                    .filter(item -> item.terminalSamples < properties.minSamplesPerCase())
                    .forEach(item -> reasons.add(item.item.id() + " 样本 " + item.terminalSamples + "/"
                            + properties.minSamplesPerCase()));
            return new RuntimeGate(runtime, GateStatus.PENDING, covered, cases.size(), samples,
                    passRate, averageScore, failedToolRate, tokens, cost, reasons);
        }
        if (passRate == null || passRate < properties.minPassRate()) {
            reasons.add("通过率未达到 " + percent(properties.minPassRate()));
        }
        if (averageScore == null || averageScore < properties.minAverageScore()) {
            reasons.add("平均评分未达到 " + properties.minAverageScore());
        }
        if (failedToolRate > properties.maxFailedToolRate()) {
            reasons.add("工具失败率高于 " + percent(properties.maxFailedToolRate()));
        }
        GateStatus status = reasons.isEmpty() ? GateStatus.PASS : GateStatus.FAIL;
        return new RuntimeGate(runtime, status, covered, cases.size(), samples,
                passRate, averageScore, failedToolRate, tokens, cost, reasons);
    }

    private static String percent(double value) {
        return Math.round(value * 1000.0) / 10.0 + "%";
    }

    private static String key(String caseId, String runtime) {
        return runtime + "\n" + caseId;
    }

    private static final class CaseAccumulator {
        private final BenchmarkCase item;
        private final String runtime;
        private int taskCount;
        private int terminalSamples;
        private int passed;
        private int failed;
        private int cancelled;
        private int scored;
        private int scoreTotal;
        private int timed;
        private long durationTotal;
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private double costEstimate;
        private int toolCalls;
        private int failedToolCalls;

        private CaseAccumulator(BenchmarkCase item, String runtime) {
            this.item = item;
            this.runtime = runtime;
        }

        private void add(TaskRecord task, UsageAccumulator usage, ToolAccumulator tools) {
            taskCount++;
            if (isEvaluationTerminal(task.getStatus())) {
                terminalSamples++;
                if (task.getStatus() == TaskStatus.DONE && task.getVerdict() == TaskVerdict.PASS) {
                    passed++;
                } else if (task.getStatus() == TaskStatus.CANCELLED) {
                    cancelled++;
                } else {
                    failed++;
                }
                if (task.getScore() != null) {
                    scored++;
                    scoreTotal += task.getScore();
                }
                if (task.getExecutionStartedAt() != null && task.getExecutionFinishedAt() != null) {
                    timed++;
                    durationTotal += Duration.between(task.getExecutionStartedAt(),
                            task.getExecutionFinishedAt()).toMillis();
                }
            }
            if (usage != null) {
                inputTokens += usage.inputTokens;
                outputTokens += usage.outputTokens;
                totalTokens += usage.totalTokens;
                costEstimate += usage.costEstimate;
            }
            if (tools != null) {
                toolCalls += tools.calls;
                failedToolCalls += tools.failed;
            }
        }

        private CaseRuntimeResult view() {
            return new CaseRuntimeResult(item.id(), item.title(), runtime, taskCount, terminalSamples,
                    passed, failed, cancelled,
                    terminalSamples == 0 ? null : (double) passed / terminalSamples,
                    scored == 0 ? null : (double) scoreTotal / scored,
                    timed == 0 ? null : (double) durationTotal / timed,
                    inputTokens, outputTokens, totalTokens, costEstimate, toolCalls, failedToolCalls);
        }
    }

    private static boolean isEvaluationTerminal(TaskStatus status) {
        return status == TaskStatus.DONE || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED || status == TaskStatus.NEEDS_REVISION;
    }

    private static final class UsageAccumulator {
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private double costEstimate;
    }

    private static final class ToolAccumulator {
        private int calls;
        private int failed;
    }
}
