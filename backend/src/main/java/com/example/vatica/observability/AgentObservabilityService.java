package com.example.vatica.observability;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;

import jakarta.persistence.criteria.Predicate;

/** 迭代 21B：为 Web 诊断工作台聚合 Span，而不是把原始模型内容直接暴露给前端。 */
@Service
public class AgentObservabilityService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Map<String, String> SPAN_SORT_FIELDS = Map.of(
            "startedAt", "startedAt", "durationMs", "durationMs", "status", "status",
            "totalTokens", "totalTokens", "costEstimate", "costEstimate", "judgeScore", "judgeScore");
    private static final java.util.Set<String> RUN_SORT_FIELDS = java.util.Set.of("spanCount", "failedSpanCount");

    public record SpanView(String spanId, String traceId, String parentSpanId, String runId,
            String taskId, Integer stepId, int attempt, String spanType, String name,
            String runtime, String agentId, String role, String modelSlotId, String skillId,
            String skillVersion, String status, String startedAt, String endedAt, long durationMs,
            String inputSummary, String outputSummary, String errorCode, String errorSummary,
            Integer inputTokens, Integer outputTokens, Integer totalTokens, Integer reasoningTokens,
            Double contextFillRatio, Double costEstimate, Integer judgeScore, String judgeVerdict) {
    }

    public record RunSummary(String traceId, String runId, String taskId, String status,
            String startedAt, String endedAt, long durationMs, String runtime, int attempt,
            int spanCount, int failedSpanCount, Integer totalTokens, Double costEstimate,
            Integer judgeScore, String judgeVerdict) {
    }

    public record OverviewView(String windowStart, String windowEnd, int runCount,
            int successCount, int failedCount, double successRate, long p50DurationMs,
            long p95DurationMs, long totalTokens, double totalCost, int failedSpanCount,
            long droppedSpanWrites, List<RunSummary> recentRuns) {
    }

    /** 迭代 28A：服务端组合查询参数。时间使用 ISO-8601，空值表示不限制。 */
    public record SpanQuery(Instant from, Instant to, String traceId, String taskId, String status,
            String spanType, String name, String runtime, String agentId, String modelSlotId,
            String skillId, String errorCode, String judgeVerdict, Long minDurationMs, Long maxDurationMs,
            Integer minJudgeScore, int page, int size, String sortBy, String direction) {

        public SpanQuery {
            page = Math.max(0, page);
            size = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
            sortBy = SPAN_SORT_FIELDS.containsKey(sortBy) || RUN_SORT_FIELDS.contains(sortBy) ? sortBy : "startedAt";
            direction = "asc".equalsIgnoreCase(direction) ? "asc" : "desc";
            minDurationMs = minDurationMs == null ? null : Math.max(0, minDurationMs);
            maxDurationMs = maxDurationMs == null ? null : Math.max(0, maxDurationMs);
            minJudgeScore = minJudgeScore == null ? null : Math.max(0, Math.min(100, minJudgeScore));
        }
    }

    public record QueryAggregate(long runCount, long successCount, long failedCount,
            double successRate, long spanCount, long failedSpanCount, long totalTokens,
            double totalCost, long p50DurationMs, long p95DurationMs) {
    }

    public record RunQueryPage(List<RunSummary> items, int page, int size, long totalRuns,
            int totalPages, QueryAggregate aggregate, String sortBy, String direction) {
    }

    public record DiagnosisFinding(String kind, String severity, String title, String evidence,
            String traceId, String spanId, String taskId) {
    }

    public record DiagnosisReport(String scope, long spanCount, long runCount,
            List<DiagnosisFinding> findings) {
    }

    private final AgentSpanRecordRepository repository;
    private final AgentObservabilityRecorder recorder;

    public AgentObservabilityService(AgentSpanRecordRepository repository,
            AgentObservabilityRecorder recorder) {
        this.repository = repository;
        this.recorder = recorder;
    }

    public List<SpanView> trace(RequestIdentity identity, String traceId) {
        return repository.findByUserIdAndOrgIdAndTraceIdOrderByStartedAtAscSpanIdAsc(
                identity.userId(), identity.orgId(), traceId).stream().map(AgentObservabilityService::view).toList();
    }

    public List<SpanView> task(RequestIdentity identity, String taskId) {
        return repository.findByUserIdAndOrgIdAndTaskIdOrderByStartedAtAscSpanIdAsc(
                identity.userId(), identity.orgId(), taskId).stream().map(AgentObservabilityService::view).toList();
    }

    public OverviewView overview(RequestIdentity identity, int limit) {
        List<AgentSpanRecord> spans = recentRecords(identity);
        List<RunSummary> runs = summarize(spans, limit);
        int success = (int) runs.stream().filter(r -> AgentSpanRecord.STATUS_SUCCESS.equals(r.status())).count();
        int failed = (int) runs.stream().filter(r -> AgentSpanRecord.STATUS_FAILED.equals(r.status())).count();
        long totalTokens = spans.stream().map(AgentSpanRecord::getTotalTokens).filter(java.util.Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        double totalCost = spans.stream().map(AgentSpanRecord::getCostEstimate).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();
        List<Long> durations = runs.stream().map(RunSummary::durationMs).filter(v -> v >= 0).sorted().toList();
        return new OverviewView(
                spans.isEmpty() ? null : spans.stream().map(AgentSpanRecord::getStartedAt).min(Comparator.naturalOrder()).orElse(null).toString(),
                spans.isEmpty() ? null : spans.stream().map(AgentSpanRecord::getStartedAt).max(Comparator.naturalOrder()).orElse(null).toString(),
                runs.size(), success, failed, runs.isEmpty() ? 0 : (double) success / runs.size(),
                percentile(durations, 0.50), percentile(durations, 0.95), totalTokens, totalCost,
                (int) spans.stream().filter(s -> AgentSpanRecord.STATUS_FAILED.equals(s.getStatus())).count(),
                recorder.droppedCount(), runs);
    }

    public List<RunSummary> runs(RequestIdentity identity, int limit) {
        return summarize(recentRecords(identity), limit);
    }

    /** 迭代 28A：组合筛选、稳定分页和过滤结果聚合均在服务端完成。 */
    public RunQueryPage queryRuns(RequestIdentity identity, SpanQuery query) {
        Specification<AgentSpanRecord> specification = specification(identity, query);
        Sort.Direction direction = "asc".equals(query.direction()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String spanSortField = SPAN_SORT_FIELDS.getOrDefault(query.sortBy(), "startedAt");
        Sort sort = Sort.by(direction, spanSortField)
                .and(Sort.by(direction, "traceId"));
        List<AgentSpanRecord> matched = repository.findAll(specification, sort);
        List<RunSummary> allRuns = summarizeAll(matched);
        Comparator<RunSummary> runComparator = runComparator(query.sortBy(), direction);
        allRuns = allRuns.stream().sorted(runComparator.thenComparing(RunSummary::traceId)).toList();
        QueryAggregate aggregate = aggregate(matched, allRuns);
        int from = Math.min(query.page() * query.size(), allRuns.size());
        int to = Math.min(from + query.size(), allRuns.size());
        List<RunSummary> items = allRuns.subList(from, to);
        return new RunQueryPage(items, query.page(), query.size(), allRuns.size(),
                allRuns.isEmpty() ? 0 : (int) Math.ceil((double) allRuns.size() / query.size()),
                aggregate, query.sortBy(), query.direction());
    }

    /** 迭代 28C：只根据已记录的 Span 事实生成规则化诊断，不调用模型。 */
    public DiagnosisReport diagnose(RequestIdentity identity, SpanQuery query) {
        List<AgentSpanRecord> spans = repository.findAll(specification(identity, query),
                Sort.by(Sort.Direction.ASC, "startedAt").and(Sort.by(Sort.Direction.ASC, "spanId")));
        List<RunSummary> runs = summarizeAll(spans);
        List<Long> durations = runs.stream().map(RunSummary::durationMs).sorted().toList();
        long slowThreshold = Math.max(5_000, percentile(durations, .95));
        List<DiagnosisFinding> findings = new java.util.ArrayList<>();
        spans.stream().filter(s -> AgentSpanRecord.STATUS_FAILED.equals(s.getStatus())).forEach(span ->
                findings.add(new DiagnosisFinding("FAILURE", "ERROR", "Span 执行失败",
                        text(span.getErrorCode(), span.getErrorSummary(), "状态为 FAILED"), span.getTraceId(), span.getSpanId(), span.getTaskId())));
        spans.stream().filter(s -> s.getDurationMs() >= slowThreshold).forEach(span ->
                findings.add(new DiagnosisFinding("SLOW", "WARN", "Span 耗时位于慢点区间",
                        "耗时 " + span.getDurationMs() + " ms，筛选结果 P95 阈值 " + slowThreshold + " ms。",
                        span.getTraceId(), span.getSpanId(), span.getTaskId())));
        spans.stream().filter(s -> s.getAttempt() > 1).forEach(span ->
                findings.add(new DiagnosisFinding("RETRY", "WARN", "检测到重试或返工尝试",
                        "attempt=" + span.getAttempt() + "，该事实来自 Span 执行次数。",
                        span.getTraceId(), span.getSpanId(), span.getTaskId())));
        spans.stream().filter(s -> s.getJudgeScore() != null && s.getJudgeScore() < 70).forEach(span ->
                findings.add(new DiagnosisFinding("QUALITY", "WARN", "Judge 评分偏低",
                        "Judge 分数 " + span.getJudgeScore() + "，结论 " + (span.getJudgeVerdict() == null ? "未记录" : span.getJudgeVerdict()) + "。",
                        span.getTraceId(), span.getSpanId(), span.getTaskId())));
        double medianCost = percentileDouble(runs.stream().map(RunSummary::costEstimate)
                .filter(java.util.Objects::nonNull).sorted().toList(), .50);
        runs.stream().filter(run -> run.costEstimate() != null && run.costEstimate() > 0
                && (medianCost == 0 || run.costEstimate() > medianCost * 2)).forEach(run ->
                findings.add(new DiagnosisFinding("COST", "INFO", "运行成本高于筛选结果中位数",
                        "成本 " + run.costEstimate() + "，筛选结果中位数约 " + medianCost + "。",
                        run.traceId(), null, run.taskId())));
        return new DiagnosisReport(query.traceId() == null ? "FILTER" : "TRACE", spans.size(), runs.size(), List.copyOf(findings));
    }

    private static String text(String code, String summary, String fallback) {
        if (code != null && summary != null) return code + "：" + summary;
        if (code != null) return code;
        return summary == null || summary.isBlank() ? fallback : summary;
    }

    private List<AgentSpanRecord> recentRecords(RequestIdentity identity) {
        return repository.findTop500ByUserIdAndOrgIdOrderByStartedAtDescSpanIdDesc(
                identity.userId(), identity.orgId());
    }

    private static List<RunSummary> summarize(List<AgentSpanRecord> records, int limit) {
        return summarizeAll(records).stream()
                .sorted(Comparator.comparing(RunSummary::startedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, Math.min(limit, 100))).toList();
    }

    private static List<RunSummary> summarizeAll(List<AgentSpanRecord> records) {
        Map<String, List<AgentSpanRecord>> groups = records.stream().collect(Collectors.groupingBy(
                AgentSpanRecord::getTraceId, LinkedHashMap::new, Collectors.toList()));
        return groups.values().stream().map(AgentObservabilityService::run).toList();
    }

    private static Comparator<RunSummary> runComparator(String sortBy, Sort.Direction direction) {
        Comparator<RunSummary> comparator = switch (sortBy) {
            case "durationMs" -> Comparator.comparingLong(RunSummary::durationMs);
            case "status" -> Comparator.comparing(RunSummary::status, Comparator.nullsLast(String::compareTo));
            case "totalTokens" -> Comparator.comparingLong(r -> value(r.totalTokens()));
            case "costEstimate" -> Comparator.comparingDouble(r -> value(r.costEstimate()));
            case "judgeScore" -> Comparator.comparingInt(r -> valueInt(r.judgeScore()));
            case "spanCount" -> Comparator.comparingInt(RunSummary::spanCount);
            case "failedSpanCount" -> Comparator.comparingInt(RunSummary::failedSpanCount);
            default -> Comparator.comparing(RunSummary::startedAt, Comparator.nullsLast(String::compareTo));
        };
        return direction == Sort.Direction.ASC ? comparator : comparator.reversed();
    }

    private static long value(Integer value) {
        return value == null ? 0 : value.longValue();
    }

    private static int valueInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static double value(Double value) {
        return value == null ? 0d : value;
    }

    private static QueryAggregate aggregate(List<AgentSpanRecord> spans, List<RunSummary> runs) {
        long success = runs.stream().filter(r -> AgentSpanRecord.STATUS_SUCCESS.equals(r.status())).count();
        long failed = runs.stream().filter(r -> AgentSpanRecord.STATUS_FAILED.equals(r.status())).count();
        List<Long> durations = runs.stream().map(RunSummary::durationMs).sorted().toList();
        long tokens = spans.stream().map(AgentSpanRecord::getTotalTokens).filter(java.util.Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        double cost = spans.stream().map(AgentSpanRecord::getCostEstimate).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum();
        return new QueryAggregate(runs.size(), success, failed, runs.isEmpty() ? 0 : (double) success / runs.size(),
                spans.size(), spans.stream().filter(s -> AgentSpanRecord.STATUS_FAILED.equals(s.getStatus())).count(),
                tokens, cost, percentile(durations, .50), percentile(durations, .95));
    }

    private static Specification<AgentSpanRecord> specification(RequestIdentity identity, SpanQuery query) {
        return (root, criteria, builder) -> {
            List<Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(builder.equal(root.get("userId"), identity.userId()));
            predicates.add(builder.equal(root.get("orgId"), identity.orgId()));
            if (query.from() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("startedAt"), query.from()));
            if (query.to() != null) predicates.add(builder.lessThan(root.get("startedAt"), query.to()));
            equal(predicates, builder, root, "traceId", query.traceId());
            equal(predicates, builder, root, "taskId", query.taskId());
            equal(predicates, builder, root, "status", query.status());
            equal(predicates, builder, root, "spanType", query.spanType());
            equal(predicates, builder, root, "runtime", query.runtime());
            equal(predicates, builder, root, "agentId", query.agentId());
            equal(predicates, builder, root, "modelSlotId", query.modelSlotId());
            equal(predicates, builder, root, "skillId", query.skillId());
            equal(predicates, builder, root, "errorCode", query.errorCode());
            equal(predicates, builder, root, "judgeVerdict", query.judgeVerdict());
            if (query.name() != null && !query.name().isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("name")), "%" + query.name().trim().toLowerCase() + "%"));
            }
            if (query.minDurationMs() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("durationMs"), query.minDurationMs()));
            if (query.maxDurationMs() != null) predicates.add(builder.lessThanOrEqualTo(root.get("durationMs"), query.maxDurationMs()));
            if (query.minJudgeScore() != null) predicates.add(builder.greaterThanOrEqualTo(root.get("judgeScore"), query.minJudgeScore()));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void equal(List<Predicate> predicates, jakarta.persistence.criteria.CriteriaBuilder builder,
            jakarta.persistence.criteria.Root<AgentSpanRecord> root, String field, String value) {
        if (value != null && !value.isBlank()) predicates.add(builder.equal(root.get(field), value.trim()));
    }

    private static RunSummary run(List<AgentSpanRecord> spans) {
        AgentSpanRecord root = spans.stream().min(Comparator.comparing(AgentSpanRecord::getStartedAt)).orElse(spans.get(0));
        AgentSpanRecord last = spans.stream().max(Comparator.comparing(AgentSpanRecord::getEndedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))).orElse(root);
        AgentSpanRecord runRoot = spans.stream().filter(s -> "TASK_RUN".equals(s.getSpanType())).findFirst().orElse(root);
        String status = runRoot.getStatus();
        Integer tokens = spans.stream().map(AgentSpanRecord::getTotalTokens).filter(java.util.Objects::nonNull)
                .reduce(0, Integer::sum);
        Double cost = spans.stream().map(AgentSpanRecord::getCostEstimate).filter(java.util.Objects::nonNull)
                .reduce(0d, Double::sum);
        long duration = last.getEndedAt() == null ? root.getDurationMs()
                : Math.max(root.getDurationMs(), last.getEndedAt().toEpochMilli() - root.getStartedAt().toEpochMilli());
        AgentSpanRecord judge = spans.stream().filter(s -> "JUDGE".equals(s.getSpanType())).findFirst().orElse(null);
        String runtime = spans.stream().map(AgentSpanRecord::getRuntime).filter(java.util.Objects::nonNull)
                .findFirst().orElse(runRoot.getRuntime());
        int attempt = spans.stream().mapToInt(AgentSpanRecord::getAttempt).max().orElse(runRoot.getAttempt());
        return new RunSummary(root.getTraceId(), runRoot.getRunId(), root.getTaskId(), status,
                instant(root.getStartedAt()), instant(last.getEndedAt()), duration, runtime, attempt,
                spans.size(), (int) spans.stream().filter(s -> AgentSpanRecord.STATUS_FAILED.equals(s.getStatus())).count(),
                tokens, cost, judge == null ? null : judge.getJudgeScore(), judge == null ? null : judge.getJudgeVerdict());
    }

    private static SpanView view(AgentSpanRecord r) {
        return new SpanView(r.getSpanId(), r.getTraceId(), r.getParentSpanId(), r.getRunId(), r.getTaskId(),
                r.getStepId(), r.getAttempt(), r.getSpanType(), r.getName(), r.getRuntime(), r.getAgentId(),
                r.getRole(), r.getModelSlotId(), r.getSkillId(), r.getSkillVersion(), r.getStatus(),
                instant(r.getStartedAt()), instant(r.getEndedAt()), r.getDurationMs(), r.getInputSummary(),
                r.getOutputSummary(), r.getErrorCode(), r.getErrorSummary(), r.getInputTokens(), r.getOutputTokens(),
                r.getTotalTokens(), r.getReasoningTokens(), r.getContextFillRatio(), r.getCostEstimate(),
                r.getJudgeScore(), r.getJudgeVerdict());
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * percentile) - 1);
        return values.get(Math.max(0, index));
    }

    private static double percentileDouble(List<Double> values, double percentile) {
        if (values.isEmpty()) return 0d;
        int index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * percentile) - 1);
        return values.get(Math.max(0, index));
    }
}
