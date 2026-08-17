package com.example.vatica.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

/**
 * 迭代 15 I15-13：平台槽位日配额（只对 platform 槽位收口，BYOK 只记录不扣额度）。
 * 单实例：DB 已落用量 + 进程内预留合计 ≤ 配额；结束按实际多退少补。
 */
@Service
public class UsageQuotaService {

    private final UsageRecordRepository repository;
    private final UsageProperties props;
    private final ConcurrentHashMap<String, AtomicLong> reservations = new ConcurrentHashMap<>();

    public UsageQuotaService(UsageRecordRepository repository, UsageProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /** 请求开始：按调用点预算预留；超限快速失败（友好 message）。 */
    public void reserve(Long userId, int estimateTokens) {
        String key = key(userId);
        long reserved = reservations.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(estimateTokens);
        if (persistedToday(userId) + reserved > props.dailyTokenQuota()) {
            reservations.get(key).addAndGet(-estimateTokens);
            throw new IllegalArgumentException(
                    "操作失败：平台模型今日 token 配额已用尽，请明日再试或改用自配模型。");
        }
    }

    /** 请求结束：按实际用量多退少补（估多退、估少补，不做负向校验，由下一次调用兜底）。 */
    public void settle(Long userId, int actualTokens, int reservedTokens) {
        String key = key(userId);
        long delta = (long) actualTokens - reservedTokens;
        reservations.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    public long quota() {
        return props.dailyTokenQuota();
    }

    public long persistedToday(Long userId) {
        Instant start = todayStart();
        return repository.findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(userId, start).stream()
                .mapToLong(UsageRecord::getTotalTokens)
                .sum();
    }

    public long reserved(Long userId) {
        return reservations.getOrDefault(key(userId), new AtomicLong()).get();
    }

    private static String key(Long userId) {
        return LocalDate.now() + ":" + userId;
    }

    private static Instant todayStart() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
