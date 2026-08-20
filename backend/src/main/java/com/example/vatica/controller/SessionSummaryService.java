package com.example.vatica.controller;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.ReasoningMode;
import com.example.vatica.context.TokenEstimator;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelInvocation;
import com.example.vatica.usage.UsageContext;

/**
 * 迭代 15 I15-9：中期滚动摘要——短期滑窗溢出后异步把旧消息压缩进
 * vatica_chat_session.summary_text，并推进 summary_through_seq 水位线。
 * 单飞（inflight）+ 事务内保存：失败不推进水位，下次追加自然重试。
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);
    private static final int MAX_NEW_CHARS_PER_MESSAGE = 500;
    private static final String SUMMARY_SYSTEM = """
            你是会话摘要 Agent。把用户与助手的对话压缩成不超过 800 字的滚动摘要，
            必须保留：用户偏好、已做决定、任务进展、待办、关键数字与路径。
            禁止编造对话中没有的信息，禁止评价用户。""";

    private final ChatSessionRecordRepository sessions;
    private final ChatMessageRecordRepository messages;
    private final ModelRegistry registry;
    private final ModelGateway modelGateway;
    private final Executor executor;
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    public SessionSummaryService(ChatSessionRecordRepository sessions,
            ChatMessageRecordRepository messages, ModelRegistry registry,
            ModelGateway modelGateway,
            @Qualifier("taskParallelExecutor") Executor executor) {
        this.sessions = sessions;
        this.messages = messages;
        this.registry = registry;
        this.modelGateway = modelGateway;
        this.executor = executor;
    }

    /** 追加消息后异步触发；单飞防止同一会话并发重复摘要。 */
    public void schedule(Long userId, Long orgId, String sessionId, long targetSeq) {
        String key = userId + ":" + sessionId;
        if (!inflight.add(key)) {
            return;
        }
        executor.execute(() -> {
            try {
                summarize(userId, orgId, sessionId, targetSeq);
            } finally {
                inflight.remove(key);
            }
        });
    }

    @Transactional
    public void summarize(Long userId, Long orgId, String sessionId, long targetSeq) {
        if (targetSeq <= 0) {
            return;
        }
        ChatSessionRecord session = sessions.findByUserIdAndSessionId(userId, sessionId)
                .orElseGet(() -> sessions.save(new ChatSessionRecord(userId, orgId, sessionId, "新会话")));
        if (targetSeq <= session.getSummaryThroughSeq()) {
            return;
        }
        List<ChatMessageRecord> all = messages.findByUserIdAndSessionIdOrderBySeqAsc(userId, sessionId);
        List<ChatMessageRecord> newRows = all.stream()
                .filter(row -> row.getSeq() > session.getSummaryThroughSeq() && row.getSeq() <= targetSeq)
                .toList();
        if (newRows.isEmpty()) {
            return;
        }
        String prompt = buildPrompt(session.getSummaryText(), newRows);
        UsageContext.set(new UsageContext.Snapshot(UsageContext.newRequestId(), "SUMMARY",
                userId, orgId, "summarizer", sessionId, null, "DISABLED", 8_000, null, true));
        try {
            String summary = modelGateway.call(new ModelInvocation(
                    registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER), SUMMARY_SYSTEM, List.of(), prompt,
                    ReasoningMode.DISABLED)).content();
            if (summary == null || summary.isBlank()) {
                return;
            }
            session.setSummaryText(summary.length() <= 2000 ? summary : summary.substring(0, 2000));
            session.setSummaryThroughSeq(targetSeq);
            session.setSummaryTokens(TokenEstimator.estimate(session.getSummaryText()));
            sessions.save(session);
            log.info("会话摘要推进：user={} session={} throughSeq={}", userId, sessionId, targetSeq);
        } catch (Exception e) {
            // 失败旧摘要/水位不动，下次追加自然重试（观测失败不阻断聊天）
            log.warn("会话摘要生成失败：user={} session={} targetSeq={}", userId, sessionId, targetSeq, e);
        } finally {
            UsageContext.clear();
        }
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

    /** 测试观测：当前单飞任务数。 */
    int inflightCount() {
        return inflight.size();
    }
}
