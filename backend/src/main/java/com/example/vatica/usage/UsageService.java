package com.example.vatica.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;

/** 迭代 15 I15-13：用户今日用量聚合。 */
@Service
public class UsageService {

    private final UsageRecordRepository repository;
    private final UsageQuotaService quotaService;

    public UsageService(UsageRecordRepository repository, UsageQuotaService quotaService) {
        this.repository = repository;
        this.quotaService = quotaService;
    }

    public record SlotTotals(int inputTokens, int outputTokens, int totalTokens, int requests) {
    }

    public record TodayView(String date, int requests, int inputTokens, int outputTokens,
            int totalTokens, int reasoningTokens, long quota, long persistedTokens, long reservedTokens,
            Map<String, SlotTotals> bySlot) {
    }

    public record RequestCallView(String id, String requestType, String slotId, Integer stepId,
            String reasoningMode, int inputTokens, int outputTokens, int totalTokens, int reasoningTokens,
            Integer contextFillRatio, long durationMs, String createdAt) {
    }

    public TodayView today(RequestIdentity identity) {
        List<UsageRecord> rows = repository.findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(
                identity.userId(), todayStart());
        int input = 0;
        int output = 0;
        int reasoning = 0;
        Map<String, SlotTotals> bySlot = new LinkedHashMap<>();
        for (UsageRecord row : rows) {
            input += row.getInputTokens();
            output += row.getOutputTokens();
            reasoning += row.getReasoningTokens();
            bySlot.merge(row.getSlotId() == null ? "unknown" : row.getSlotId(),
                    new SlotTotals(row.getInputTokens(), row.getOutputTokens(), row.getTotalTokens(), 1),
                    (a, b) -> new SlotTotals(a.inputTokens() + b.inputTokens(),
                            a.outputTokens() + b.outputTokens(), a.totalTokens() + b.totalTokens(),
                            a.requests() + b.requests()));
        }
        return new TodayView(LocalDate.now().toString(), rows.size(), input, output, input + output,
                reasoning, quotaService.quota(), quotaService.persistedToday(identity.userId()),
                quotaService.reserved(identity.userId()), bySlot);
    }

    public List<RequestCallView> requestCalls(RequestIdentity identity, String requestId) {
        return repository.findByUserIdAndRequestIdOrderByCreatedAtAsc(identity.userId(), requestId).stream()
                .map(row -> new RequestCallView(row.getId(), row.getRequestType(), row.getSlotId(),
                        row.getStepId(), row.getReasoningMode(), row.getInputTokens(), row.getOutputTokens(),
                        row.getTotalTokens(), row.getReasoningTokens(), row.getContextFillRatio(),
                        row.getDurationMs(), row.getCreatedAt() == null ? null : row.getCreatedAt().toString()))
                .toList();
    }

    private static Instant todayStart() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
