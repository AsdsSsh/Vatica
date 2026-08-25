package com.example.vatica.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

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
    private static final String SUMMARY_SYSTEM = """
            你是会话摘要 Agent。把用户与助手的对话压缩成不超过 800 字的滚动摘要，
            必须保留：用户偏好、已做决定、任务进展、待办、关键数字与路径。
            禁止编造对话中没有的信息，禁止评价用户。""";

    private final ChatSessionRecordRepository sessions;
    private final ChatMessageRecordRepository messages;
    private final ModelRegistry registry;
    private final ModelGateway modelGateway;
    private final Executor executor;
    private final ChatProperties.Summary properties;

    /** 同一 JVM 内的单飞协调；持久化水位才是重启后的事实。 */
    private final Map<String, SummaryWork> scheduled = new ConcurrentHashMap<>();

    public SessionSummaryService(ChatSessionRecordRepository sessions,
            ChatMessageRecordRepository messages, ModelRegistry registry,
            ModelGateway modelGateway,
            @Qualifier("taskParallelExecutor") Executor executor,
            ChatProperties properties) {
        this.sessions = sessions;
        this.messages = messages;
        this.registry = registry;
        this.modelGateway = modelGateway;
        this.executor = executor;
        this.properties = properties.summary();
    }

    /** 追加消息后异步触发；新目标会合并进同一会话的待处理水位。 */
    public void schedule(Long userId, Long orgId, String sessionId, long targetSeq) {
        scheduleInternal(userId, orgId, sessionId, targetSeq, 0);
    }

    private void scheduleInternal(Long userId, Long orgId, String sessionId, long targetSeq, int retry) {
        if (targetSeq <= 0) {
            return;
        }
        String key = keyOf(userId, sessionId);
        SummaryWork work = scheduled.computeIfAbsent(key,
                ignored -> new SummaryWork(userId, orgId, sessionId, targetSeq));
        boolean shouldStart;
        try {
            synchronized (work.lock) {
                work.requestedTarget = Math.max(work.requestedTarget, targetSeq);
                shouldStart = requestTarget(work, targetSeq);
            }
        } catch (RuntimeException e) {
            scheduled.remove(key, work);
            log.warn("会话摘要请求登记失败：user={} session={}", userId, sessionId, e);
            return;
        }
        if (shouldStart && work.start()) {
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
            if (!requestTarget(work, targetSeq)) {
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
                            markFailure(work, SessionSummaryFailureCode.TRANSIENT, null);
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
                markFailure(work, SessionSummaryFailureCode.TRANSIENT, null);
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

        String summary;
        try {
            summary = invokeModel(work, batch);
        } catch (Exception e) {
            SessionSummaryFailureCode failureCode = classifyFailure(e);
            Instant nextRetryAt = retryable(failureCode) && retry < properties.maxAutoRetries()
                    ? Instant.now().plus(retryDelay(retry)) : null;
            synchronized (work.lock) {
                markFailure(work, failureCode, nextRetryAt);
            }
            log.warn("会话摘要生成失败：user={} session={} targetSeq={} code={}",
                    work.userId, work.sessionId, batch.requestedThroughSeq, failureCode, e);
            return SummaryOutcome.failed(nextRetryAt);
        }

        if (summary == null || summary.isBlank()) {
            Instant nextRetryAt = retry < properties.maxAutoRetries()
                    ? Instant.now().plus(retryDelay(retry)) : null;
            synchronized (work.lock) {
                markFailure(work, SessionSummaryFailureCode.EMPTY_RESPONSE, nextRetryAt);
            }
            log.warn("会话摘要返回空内容：user={} session={} targetSeq={}",
                    work.userId, work.sessionId, batch.requestedThroughSeq);
            return SummaryOutcome.failed(nextRetryAt);
        }

        long through;
        synchronized (work.lock) {
            through = markSuccess(work, batch, summary);
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
                .findByUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                        work.userId, work.sessionId, session.getSummaryThroughSeq(), requested,
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

    private boolean requestTarget(SummaryWork work, long targetSeq) {
        ChatSessionRecord session = findOrCreate(work);
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

    private long markSuccess(SummaryWork work, SummaryBatch batch, String summary) {
        ChatSessionRecord session = findOrCreate(work);
        if (session.getSummaryThroughSeq() != batch.previousThroughSeq()) {
            // 同会话的另一条恢复路径已推进水位，当前结果不覆盖它。
            return session.getSummaryThroughSeq();
        }
        String normalized = summary.length() <= MAX_SUMMARY_CHARS ? summary : summary.substring(0, MAX_SUMMARY_CHARS);
        long through = batch.rows().getLast().getSeq();
        session.setSummaryText(normalized);
        session.setSummaryThroughSeq(through);
        session.setSummaryTokens(TokenEstimator.estimate(normalized));
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
    }

    private void markFailure(SummaryWork work, SessionSummaryFailureCode code, Instant nextRetryAt) {
        ChatSessionRecord session = findOrCreate(work);
        session.setSummaryStatus(SessionSummaryStatus.FAILED);
        session.setSummaryFailureCode(code);
        session.setSummaryNextRetryAt(nextRetryAt);
        sessions.save(session);
    }

    private static void markHealthy(ChatSessionRecord session) {
        session.setSummaryStatus(SessionSummaryStatus.SUCCESS);
        session.setSummaryFailureCode(SessionSummaryFailureCode.NONE);
        session.setSummaryNextRetryAt(null);
    }

    private ChatSessionRecord findOrCreate(SummaryWork work) {
        return sessions.findByUserIdAndSessionId(work.userId, work.sessionId)
                .orElseGet(() -> sessions.save(new ChatSessionRecord(
                        work.userId, work.orgId, work.sessionId, "新会话")));
    }

    private String invokeModel(SummaryWork work, SummaryBatch batch) {
        UsageContext.set(new UsageContext.Snapshot(UsageContext.newRequestId(), "SUMMARY",
                work.userId, work.orgId, "summarizer", work.sessionId, null, "DISABLED", 8_000, null, true));
        try {
            return modelGateway.call(new ModelInvocation(
                    registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER), SUMMARY_SYSTEM, List.of(),
                    buildPrompt(batch.existingSummary(), batch.rows()), ReasoningMode.DISABLED)).content();
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
                                work.requestedTarget, retry + 1));
            }
            return;
        }

        boolean continueBatch;
        synchronized (work.lock) {
            ChatSessionRecord session = findOrCreate(work);
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
        return sb.append("\n请输出滚动摘要。").toString();
    }

    /** 测试观测：当前已登记的单飞会话数。 */
    int inflightCount() {
        return scheduled.size();
    }

    private static String keyOf(Long userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private record SummaryBatch(String existingSummary, long previousThroughSeq,
            long requestedThroughSeq, List<ChatMessageRecord> rows) {
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
