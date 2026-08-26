package com.example.vatica.controller;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.ReasoningMode;
import com.example.vatica.context.TokenEstimator;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelInvocation;
import com.example.vatica.usage.UsageContext;

/**
 * 迭代 29A：中期滚动摘要。
 *
 * <p>摘要是可重建缓存，聊天原文仍是事实源。成功水位只在有效模型输出写入后前进；
 * 失败时保留旧摘要和成功水位，并将未覆盖区间交给 {@link JpaSessionMemory} 的上下文降级路径。</p>
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);
    private static final int MAX_NEW_CHARS_PER_MESSAGE = 500;
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private static final int MAX_LOCAL_SEGMENT_CHARS = 1_200;
    private static final String SUMMARY_STRATEGY_VERSION = "summary-v2-json-local";
    private static final String SUMMARY_SYSTEM = """
            你是会话摘要 Agent。你只处理给定的聊天原文，禁止编造或评价用户。
            必须保留：用户偏好、已做决定、任务进展、待办、关键数字与路径。
            只输出一个 JSON 对象，不要 Markdown、解释或代码块，字段必须为：
            - overview：结合已有摘要后的会话滚动总览，不超过 800 字；
            - localSegment：只描述本次新增对话区间，不超过 500 字，必须可独立理解。
            """;

    private final ChatSessionRecordRepository sessions;
    private final ChatMessageRecordRepository messages;
    private final ChatSummarySegmentRecordRepository segments;
    private final ModelRegistry registry;
    private final ModelGateway modelGateway;
    private final Executor executor;
    private final ChatProperties.Summary properties;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    /** 同一 JVM 内的单飞协调；持久化水位才是重启后的事实。 */
    private final Map<String, SummaryWork> scheduled = new ConcurrentHashMap<>();

    public SessionSummaryService(ChatSessionRecordRepository sessions,
            ChatMessageRecordRepository messages, ChatSummarySegmentRecordRepository segments,
            ModelRegistry registry,
            ModelGateway modelGateway,
            @Qualifier("taskParallelExecutor") Executor executor,
            ChatProperties properties, ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.messages = messages;
        this.segments = segments;
        this.registry = registry;
        this.modelGateway = modelGateway;
        this.executor = executor;
        this.properties = properties.summary();
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 追加消息后异步触发；新目标会合并进同一会话的待处理水位。 */
    public void schedule(Long userId, Long orgId, String sessionId, long targetSeq) {
        scheduleInternal(userId, orgId, sessionId, targetSeq, 0, true);
    }

    private void scheduleInternal(Long userId, Long orgId, String sessionId, long targetSeq, int retry,
            boolean allowCreate) {
        if (targetSeq <= 0) {
            return;
        }
        String key = keyOf(userId, orgId, sessionId);
        SummaryWork work = scheduled.computeIfAbsent(key,
                ignored -> new SummaryWork(userId, orgId, sessionId, targetSeq));
        boolean shouldStart;
        try {
            synchronized (work.lock) {
                work.requestedTarget = Math.max(work.requestedTarget, targetSeq);
                shouldStart = requestTarget(work, targetSeq, allowCreate);
            }
        } catch (RuntimeException e) {
            scheduled.remove(key, work);
            log.warn("会话摘要请求登记失败：user={} session={}", userId, sessionId, e);
            return;
        }
        if (!shouldStart) {
            synchronized (work.lock) {
                if (!work.running) {
                    scheduled.remove(key, work);
                }
            }
            return;
        }
        if (work.start()) {
            submitAttempt(key, work, retry);
        }
    }

    /** 供测试与运维手动补偿调用：只处理一个受控批次。 */
    public void summarize(Long userId, Long orgId, String sessionId, long targetSeq) {
        if (targetSeq <= 0) {
            return;
        }
        SummaryWork work = new SummaryWork(userId, orgId, sessionId, targetSeq);
        synchronized (work.lock) {
            if (!requestTarget(work, targetSeq, true)) {
                return;
            }
        }
        summarizeBatch(work, 0);
    }

    private void submitAttempt(String key, SummaryWork work, int retry) {
        try {
            executor.execute(() -> {
                try {
                    SummaryOutcome outcome = summarizeBatch(work, retry);
                    completeAttempt(key, work, outcome, retry);
                } catch (RuntimeException e) {
                    // 数据库/调度异常也必须清理单飞状态，否则该会话会永久停止补偿。
                    scheduled.remove(key, work);
                    try {
                        synchronized (work.lock) {
                            markFailure(work, SessionSummaryFailureCode.TRANSIENT, null, null);
                        }
                    } catch (RuntimeException persistFailure) {
                        log.warn("会话摘要异常状态写回失败：user={} session={}",
                                work.userId, work.sessionId, persistFailure);
                    }
                    log.warn("会话摘要后台任务异常：user={} session={}", work.userId, work.sessionId, e);
                }
            });
        } catch (RuntimeException e) {
            scheduled.remove(key, work);
            synchronized (work.lock) {
                markFailure(work, SessionSummaryFailureCode.TRANSIENT, null, null);
            }
            log.warn("会话摘要调度被拒绝：user={} session={}", work.userId, work.sessionId, e);
        }
    }

    /** 一次调用只摘要一个页大小的历史，避免恢复后构建不受控的大 Prompt。 */
    private SummaryOutcome summarizeBatch(SummaryWork work, int retry) {
        SummaryBatch batch;
        synchronized (work.lock) {
            batch = claimBatch(work);
        }
        if (batch == null) {
            return SummaryOutcome.noWork();
        }

        ModelSummaryResponse response;
        try {
            response = invokeModel(work, batch);
        } catch (Exception e) {
            SessionSummaryFailureCode failureCode = classifyFailure(e);
            Instant nextRetryAt = retryable(failureCode) && retry < properties.maxAutoRetries()
                    ? Instant.now().plus(retryDelay(retry)) : null;
            synchronized (work.lock) {
                markFailure(work, failureCode, nextRetryAt, batch.previousThroughSeq());
            }
            log.warn("会话摘要生成失败：user={} session={} targetSeq={} code={}",
                    work.userId, work.sessionId, batch.requestedThroughSeq, failureCode, e);
            return SummaryOutcome.failed(nextRetryAt);
        }

        if (response.content() == null || response.content().isBlank()) {
            Instant nextRetryAt = retry < properties.maxAutoRetries()
                    ? Instant.now().plus(retryDelay(retry)) : null;
            synchronized (work.lock) {
                markFailure(work, SessionSummaryFailureCode.EMPTY_RESPONSE, nextRetryAt,
                        batch.previousThroughSeq());
            }
            log.warn("会话摘要返回空内容：user={} session={} targetSeq={}",
                    work.userId, work.sessionId, batch.requestedThroughSeq);
            return SummaryOutcome.failed(nextRetryAt);
        }

        SummaryPayload payload = parsePayload(work, batch, response.content());
        long through;
        synchronized (work.lock) {
            through = markSuccess(work, batch, payload, response.modelId());
        }
        log.info("会话摘要推进：user={} session={} throughSeq={}", work.userId, work.sessionId, through);
        return SummaryOutcome.success();
    }

    /** 登记请求和尝试元数据；此处不包住远程模型调用。 */
    private SummaryBatch claimBatch(SummaryWork work) {
        ChatSessionRecord session = findOrCreate(work);
        long requested = Math.max(work.requestedTarget, session.getSummaryRequestedThroughSeq());
        if (requested <= session.getSummaryThroughSeq()) {
            markHealthy(session);
            sessions.save(session);
            return null;
        }

        session.setSummaryRequestedThroughSeq(requested);
        session.setSummaryStatus(SessionSummaryStatus.PENDING);
        session.setSummaryAttemptCount(session.getSummaryAttemptCount() + 1);
        session.setSummaryLastAttemptAt(Instant.now());
        session.setSummaryNextRetryAt(null);
        sessions.save(session);

        List<ChatMessageRecord> rows = messages
                .findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                        work.orgId, work.userId, work.sessionId, session.getSummaryThroughSeq(), requested,
                        org.springframework.data.domain.PageRequest.of(0, properties.maxBatchMessages()));
        if (rows.isEmpty()) {
            // 防御性收口：不可能的超前目标不能让会话永久卡在 PENDING。
            session.setSummaryRequestedThroughSeq(session.getSummaryThroughSeq());
            markHealthy(session);
            sessions.save(session);
            return null;
        }
        return new SummaryBatch(session.getSummaryText(), session.getSummaryThroughSeq(), requested, rows);
    }

    private boolean requestTarget(SummaryWork work, long targetSeq, boolean allowCreate) {
        ChatSessionRecord session = allowCreate ? findOrCreate(work)
                : sessions.findByUserIdAndOrgIdAndSessionId(work.userId, work.orgId, work.sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        long requested = Math.max(Math.max(session.getSummaryRequestedThroughSeq(), work.requestedTarget), targetSeq);
        work.requestedTarget = requested;
        if (requested <= session.getSummaryThroughSeq()) {
            markHealthy(session);
            sessions.save(session);
            return false;
        }
        session.setSummaryRequestedThroughSeq(requested);
        session.setSummaryStatus(SessionSummaryStatus.PENDING);
        session.setSummaryNextRetryAt(null);
        sessions.save(session);
        return true;
    }

    /**
     * 远程调用完成后，用一个短事务同时追加 L1 段和推进旧会话水位。
     * 这样不会出现“水位已覆盖、局部段却缺失”的假成功；事务不覆盖远程模型调用。
     */
    private long markSuccess(SummaryWork work, SummaryBatch batch, SummaryPayload payload, String modelId) {
        String sourceFingerprint = sourceFingerprint(batch.rows());
        Long persistedThrough = transactions.execute(status -> {
            ChatSessionRecord session = sessions.findForUpdate(work.userId, work.orgId, work.sessionId).orElse(null);
            if (session == null) {
                return -1L;
            }
            if (session.getSummaryThroughSeq() != batch.previousThroughSeq()) {
                // 同会话的另一条恢复路径已推进水位，当前结果不能覆盖或重复写段。
                return session.getSummaryThroughSeq();
            }

            long start = batch.rows().getFirst().getSeq();
            long through = batch.rows().getLast().getSeq();
            String overview = bound(payload.overview(), MAX_SUMMARY_CHARS);
            String localSegment = bound(payload.localSegment(), MAX_LOCAL_SEGMENT_CHARS);
            segments.save(new ChatSummarySegmentRecord(
                    work.orgId, work.userId, work.sessionId, ChatSummarySegmentLevel.L1_LOCAL,
                    start, through, localSegment, TokenEstimator.estimate(localSegment), batch.rows().size(),
                    sourceFingerprint, SUMMARY_STRATEGY_VERSION, bound(modelId, 160)));

            // 旧字段继续承担 L3 滚动总览与连续水位，保证现有聊天恢复路径保持兼容。
            session.setSummaryText(overview);
            session.setSummaryThroughSeq(through);
            session.setSummaryTokens(TokenEstimator.estimate(overview));
            session.setSummaryLastSuccessAt(Instant.now());
            session.setSummaryNextRetryAt(null);
            if (session.getSummaryRequestedThroughSeq() <= through) {
                markHealthy(session);
            } else {
                session.setSummaryStatus(SessionSummaryStatus.PENDING);
                session.setSummaryFailureCode(SessionSummaryFailureCode.NONE);
            }
            sessions.save(session);
            return through;
        });
        if (persistedThrough == null) {
            throw new IllegalStateException("会话摘要成功状态未能持久化");
        }
        return persistedThrough;
    }

    private void markFailure(SummaryWork work, SessionSummaryFailureCode code, Instant nextRetryAt,
            Long expectedThroughSeq) {
        transactions.executeWithoutResult(status -> sessions
                .findForUpdate(work.userId, work.orgId, work.sessionId)
                .ifPresent(session -> {
                    if (expectedThroughSeq != null && session.getSummaryThroughSeq() != expectedThroughSeq) {
                        return;
                    }
                    if (session.getSummaryRequestedThroughSeq() <= session.getSummaryThroughSeq()) {
                        return;
                    }
                    session.setSummaryStatus(SessionSummaryStatus.FAILED);
                    session.setSummaryFailureCode(code);
                    session.setSummaryNextRetryAt(nextRetryAt);
                    sessions.save(session);
                }));
    }

    private static void markHealthy(ChatSessionRecord session) {
        session.setSummaryStatus(SessionSummaryStatus.SUCCESS);
        session.setSummaryFailureCode(SessionSummaryFailureCode.NONE);
        session.setSummaryNextRetryAt(null);
    }

    private ChatSessionRecord findOrCreate(SummaryWork work) {
        ChatSessionRecord existing = sessions
                .findByUserIdAndOrgIdAndSessionId(work.userId, work.orgId, work.sessionId)
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            return sessions.save(new ChatSessionRecord(work.userId, work.orgId, work.sessionId, "新会话"));
        } catch (DataIntegrityViolationException race) {
            // 多实例首次摘要可能同时插入；唯一键胜者提交后，失败方只重读同租户会话。
            return sessions.findByUserIdAndOrgIdAndSessionId(work.userId, work.orgId, work.sessionId)
                    .orElseThrow(() -> race);
        }
    }

    private ModelSummaryResponse invokeModel(SummaryWork work, SummaryBatch batch) {
        UsageContext.set(new UsageContext.Snapshot(UsageContext.newRequestId(), "SUMMARY",
                work.userId, work.orgId, "summarizer", work.sessionId, null, "DISABLED", 8_000, null, true));
        try {
            ModelSlot slot = registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER);
            String content = modelGateway.call(new ModelInvocation(slot, SUMMARY_SYSTEM, List.of(),
                    buildPrompt(batch.existingSummary(), batch.rows()), ReasoningMode.DISABLED)).content();
            return new ModelSummaryResponse(content, modelIdOf(slot));
        } finally {
            UsageContext.clear();
        }
    }

    private void completeAttempt(String key, SummaryWork work, SummaryOutcome outcome, int retry) {
        if (outcome.failed()) {
            scheduled.remove(key, work);
            if (outcome.nextRetryAt() != null) {
                Duration delay = Duration.between(Instant.now(), outcome.nextRetryAt());
                CompletableFuture.delayedExecutor(Math.max(0, delay.toMillis()),
                        java.util.concurrent.TimeUnit.MILLISECONDS, executor)
                        .execute(() -> scheduleInternal(work.userId, work.orgId, work.sessionId,
                                work.requestedTarget, retry + 1, false));
            }
            return;
        }

        boolean continueBatch;
        synchronized (work.lock) {
            ChatSessionRecord session = sessions
                    .findByUserIdAndOrgIdAndSessionId(work.userId, work.orgId, work.sessionId)
                    .orElse(null);
            if (session == null) {
                work.finish();
                scheduled.remove(key, work);
                return;
            }
            work.requestedTarget = Math.max(work.requestedTarget, session.getSummaryRequestedThroughSeq());
            continueBatch = work.requestedTarget > session.getSummaryThroughSeq();
            if (!continueBatch) {
                work.finish();
                scheduled.remove(key, work);
            }
        }
        if (continueBatch) {
            submitAttempt(key, work, 0);
        }
    }

    private Duration retryDelay(int retry) {
        long multiplier = 1L << Math.min(retry, 4);
        return properties.retryInitialBackoff().multipliedBy(multiplier);
    }

    private static boolean retryable(SessionSummaryFailureCode code) {
        return code == SessionSummaryFailureCode.EMPTY_RESPONSE
                || code == SessionSummaryFailureCode.TIMEOUT
                || code == SessionSummaryFailureCode.TRANSIENT
                || code == SessionSummaryFailureCode.UNKNOWN;
    }

    private static SessionSummaryFailureCode classifyFailure(Exception error) {
        String text = (error.getClass().getName() + " " + error.getMessage()).toLowerCase();
        if (text.contains("timeout") || text.contains("timed out")) {
            return SessionSummaryFailureCode.TIMEOUT;
        }
        if (text.contains("401") || text.contains("403") || text.contains("unauthorized")
                || text.contains("forbidden") || text.contains("configuration")) {
            return SessionSummaryFailureCode.CONFIGURATION;
        }
        if (text.contains("connect") || text.contains("socket") || text.contains("429")
                || text.contains("502") || text.contains("503") || text.contains("504")) {
            return SessionSummaryFailureCode.TRANSIENT;
        }
        return SessionSummaryFailureCode.UNKNOWN;
    }

    private SummaryPayload parsePayload(SummaryWork work, SummaryBatch batch, String content) {
        try {
            JsonNode root = mapper.readTree(jsonObjectCandidate(content));
            String overview = root.path("overview").asText("").trim();
            String localSegment = root.path("localSegment").asText("").trim();
            if (!overview.isBlank() && !localSegment.isBlank()) {
                return new SummaryPayload(overview, localSegment);
            }
        } catch (Exception ignored) {
            // 兼容旧模型的纯文本输出，下面统一记录不含正文的脱敏警告。
        }
        String fallback = bound(content, MAX_SUMMARY_CHARS);
        log.warn("会话摘要 JSON 解析失败，使用纯文本兼容回退：user={} session={} startSeq={} endSeq={}",
                work.userId, work.sessionId, batch.rows().getFirst().getSeq(), batch.rows().getLast().getSeq());
        return new SummaryPayload(mergeLegacyOverview(batch.existingSummary(), fallback),
                bound(content, MAX_LOCAL_SEGMENT_CHARS));
    }

    private static String buildPrompt(String existingSummary, List<ChatMessageRecord> rows) {
        StringBuilder sb = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("已有摘要：\n").append(existingSummary).append("\n\n");
        }
        sb.append("新增对话：\n");
        for (ChatMessageRecord row : rows) {
            String content = row.getContent() == null ? "" : row.getContent();
            if (content.length() > MAX_NEW_CHARS_PER_MESSAGE) {
                content = content.substring(0, MAX_NEW_CHARS_PER_MESSAGE) + "…";
            }
            sb.append("[").append(row.getSeq()).append("] ")
                    .append("USER".equals(row.getRole()) ? "用户" : "助手").append("：")
                    .append(content).append('\n');
        }
        return sb.append("\n请仅输出包含 overview 与 localSegment 的 JSON 对象。").toString();
    }

    private static String jsonObjectCandidate(String content) {
        String trimmed = content == null ? "" : content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        return start >= 0 && end > start ? trimmed.substring(start, end + 1) : trimmed;
    }

    private static String bound(String value, int maxChars) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    /**
     * 旧模型只会返回一段纯文本，无法证明其中已包含旧滚动摘要。
     * 因此兼容分支保守拼接旧总览，避免格式降级时倒退已成功覆盖的会话信息。
     */
    private static String mergeLegacyOverview(String existingSummary, String fallback) {
        String existing = existingSummary == null ? "" : existingSummary.trim();
        if (existing.isBlank()) {
            return fallback;
        }
        if (fallback.isBlank()) {
            return bound(existing, MAX_SUMMARY_CHARS);
        }
        return bound(existing + "\n" + fallback, MAX_SUMMARY_CHARS);
    }

    private static String modelIdOf(ModelSlot slot) {
        if (slot == null) {
            return "unknown";
        }
        String model = slot.model();
        return model == null || model.isBlank() ? (slot.id() == null ? "unknown" : slot.id()) : model.trim();
    }

    /** 记录来源范围而非正文；消息变更后可以检测摘要段是否仍可复用。 */
    private static String sourceFingerprint(List<ChatMessageRecord> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ChatMessageRecord row : rows) {
                updateDigest(digest, Long.toString(row.getSeq()));
                updateDigest(digest, row.getRole());
                updateDigest(digest, row.getContent());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 0);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(bytes);
    }

    /** 测试观测：当前已登记的单飞会话数。 */
    int inflightCount() {
        return scheduled.size();
    }

    /** 会话删除后取消后续批次；在途结果还会通过行锁与存在性检查被丢弃。 */
    void cancel(Long userId, Long orgId, String sessionId) {
        SummaryWork work = scheduled.remove(keyOf(userId, orgId, sessionId));
        if (work != null) {
            synchronized (work.lock) {
                work.finish();
            }
        }
    }

    private static String keyOf(Long userId, Long orgId, String sessionId) {
        return orgId + ":" + userId + ":" + sessionId;
    }

    private record SummaryBatch(String existingSummary, long previousThroughSeq,
            long requestedThroughSeq, List<ChatMessageRecord> rows) {
    }

    private record ModelSummaryResponse(String content, String modelId) {
    }

    private record SummaryPayload(String overview, String localSegment) {
    }

    private record SummaryOutcome(boolean failed, Instant nextRetryAt) {
        static SummaryOutcome noWork() {
            return new SummaryOutcome(false, null);
        }

        static SummaryOutcome success() {
            return new SummaryOutcome(false, null);
        }

        static SummaryOutcome failed(Instant nextRetryAt) {
            return new SummaryOutcome(true, nextRetryAt);
        }
    }

    private static final class SummaryWork {
        private final Long userId;
        private final Long orgId;
        private final String sessionId;
        private final Object lock = new Object();
        private long requestedTarget;
        private boolean running;

        private SummaryWork(Long userId, Long orgId, String sessionId, long requestedTarget) {
            this.userId = userId;
            this.orgId = orgId;
            this.sessionId = sessionId;
            this.requestedTarget = requestedTarget;
        }

        private boolean start() {
            synchronized (lock) {
                if (running) {
                    return false;
                }
                running = true;
                return true;
            }
        }

        private void finish() {
            running = false;
        }
    }
}
