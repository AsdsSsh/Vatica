package com.example.vatica.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.task.TaskRecord;
import com.example.vatica.task.TaskRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 15 I15-13：用户今日用量聚合。 */
@Service
public class UsageService {

    private final UsageRecordRepository repository;
    private final UsageQuotaService quotaService;
    private final TaskRecordRepository taskRepository;
    private final ObjectMapper mapper;

    public UsageService(UsageRecordRepository repository, UsageQuotaService quotaService,
            TaskRecordRepository taskRepository, ObjectMapper mapper) {
        this.repository = repository;
        this.quotaService = quotaService;
        this.taskRepository = taskRepository;
        this.mapper = mapper;
    }

    public record SlotTotals(int inputTokens, int outputTokens, int totalTokens, int requests) {
    }

    /** 迭代 17C：角色最小运行基准，质量来自任务 Judge 结果，成本来自 usage 估算。 */
    public record RoleTotals(String agentId, String role, int inputTokens, int outputTokens, int totalTokens,
            int requests, long durationMs, double costEstimate, int taskCount, int passedTasks,
            Double passRate) {
    }

    public record TodayView(String date, int requests, int inputTokens, int outputTokens,
            int totalTokens, int reasoningTokens, long quota, long persistedTokens, long reservedTokens,
            Map<String, SlotTotals> bySlot, Map<String, RoleTotals> byRole) {
    }

    public record RequestCallView(String id, String requestType, String slotId, Integer stepId,
            String reasoningMode, String agentId, String role, String fallbackReason, int inputTokens,
            int outputTokens, int totalTokens, int reasoningTokens, Integer contextFillRatio, long durationMs,
            String createdAt) {
    }

    public TodayView today(RequestIdentity identity) {
        List<UsageRecord> rows = repository.findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(
                identity.userId(), todayStart());
        int input = 0;
        int output = 0;
        int reasoning = 0;
        Map<String, SlotTotals> bySlot = new LinkedHashMap<>();
        Map<String, RoleAccumulator> byRole = new LinkedHashMap<>();
        for (UsageRecord row : rows) {
            input += row.getInputTokens();
            output += row.getOutputTokens();
            reasoning += row.getReasoningTokens();
            bySlot.merge(row.getSlotId() == null ? "unknown" : row.getSlotId(),
                    new SlotTotals(row.getInputTokens(), row.getOutputTokens(), row.getTotalTokens(), 1),
                            (a, b) -> new SlotTotals(a.inputTokens() + b.inputTokens(),
                                    a.outputTokens() + b.outputTokens(), a.totalTokens() + b.totalTokens(),
                                    a.requests() + b.requests()));
            String agentId = row.getAgentId() == null || row.getAgentId().isBlank() ? "unknown" : row.getAgentId();
            String role = row.getRole() == null || row.getRole().isBlank() ? agentId : row.getRole();
            RoleAccumulator acc = byRole.computeIfAbsent(agentId, key -> new RoleAccumulator(agentId, role));
            acc.input += row.getInputTokens();
            acc.output += row.getOutputTokens();
            acc.total += row.getTotalTokens();
            acc.requests++;
            acc.durationMs += row.getDurationMs();
            acc.cost += row.getCostEstimate();
        }
        enrichQuality(identity.userId(), byRole);
        Map<String, RoleTotals> roleTotals = byRole.values().stream().collect(java.util.stream.Collectors.toMap(
                acc -> acc.agentId, RoleAccumulator::view, (left, right) -> left, LinkedHashMap::new));
        return new TodayView(LocalDate.now().toString(), rows.size(), input, output, input + output,
                reasoning, quotaService.quota(), quotaService.persistedToday(identity.userId()),
                quotaService.reserved(identity.userId()), bySlot, roleTotals);
    }

    public List<RequestCallView> requestCalls(RequestIdentity identity, String requestId) {
        return repository.findByUserIdAndRequestIdOrderByCreatedAtAsc(identity.userId(), requestId).stream()
                .map(row -> new RequestCallView(row.getId(), row.getRequestType(), row.getSlotId(),
                        row.getStepId(), row.getReasoningMode(), row.getAgentId(), row.getRole(),
                        row.getFallbackReason(), row.getInputTokens(), row.getOutputTokens(), row.getTotalTokens(),
                        row.getReasoningTokens(), row.getContextFillRatio(),
                        row.getDurationMs(), row.getCreatedAt() == null ? null : row.getCreatedAt().toString()))
                .toList();
    }

    private void enrichQuality(Long userId, Map<String, RoleAccumulator> byRole) {
        if (taskRepository == null) {
            return;
        }
        for (TaskRecord task : taskRepository.findByUserId(userId)) {
            try {
                JsonNode steps = mapper.readTree(task.getPlanJson()).path("steps");
                if (!steps.isArray()) {
                    continue;
                }
                java.util.Set<String> agents = new java.util.LinkedHashSet<>();
                steps.forEach(step -> {
                    String agent = step.path("agent").asText("");
                    if (!agent.isBlank()) {
                        agents.add(agent);
                    }
                });
                for (String agent : agents) {
                    RoleAccumulator acc = byRole.computeIfAbsent(agent, key -> new RoleAccumulator(agent, agent));
                    acc.taskCount++;
                    if (task.getVerdict() != null && "PASS".equals(task.getVerdict().name())) {
                        acc.passedTasks++;
                    }
                }
            } catch (Exception ignored) {
                // 历史计划损坏不影响 usage 查询，质量基准按可解析任务统计。
            }
        }
    }

    private static final class RoleAccumulator {
        private final String agentId;
        private final String role;
        private int input;
        private int output;
        private int total;
        private int requests;
        private long durationMs;
        private double cost;
        private int taskCount;
        private int passedTasks;

        private RoleAccumulator(String agentId, String role) {
            this.agentId = agentId;
            this.role = role;
        }

        private RoleTotals view() {
            return new RoleTotals(agentId, role, input, output, total, requests, durationMs, cost, taskCount,
                    passedTasks, taskCount == 0 ? null : ((double) passedTasks / taskCount));
        }
    }

    private static Instant todayStart() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
