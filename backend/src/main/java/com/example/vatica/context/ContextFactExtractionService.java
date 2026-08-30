package com.example.vatica.context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.ReasoningMode;
import com.example.vatica.controller.ChatMessageRecord;
import com.example.vatica.controller.ChatMessageRecordRepository;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelInvocation;
import com.example.vatica.usage.UsageContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 迭代 34：Agent 推断事实的异步后置抽取——补齐 AGENT_DERIVED 的生产者。
 *
 * <p>轮次收尾后用一个独立小调用从本轮原文抽取候选结论，以 {@code AGENT_DERIVED} 入库；
 * {@link ContextFactService} 会强制压成 {@code NEEDS_REFRESH}，只有用户确认才进入模型上下文。
 * 因此本服务尽力而为：失败不重试、不阻塞聊天；候选级容错，坏候选跳过不影响其余。</p>
 */
@Service
public class ContextFactExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ContextFactExtractionService.class);
    private static final int MAX_TURN_MESSAGES = 20;
    private static final int MAX_MESSAGE_CHARS = 2_000;
    private static final int MAX_EXISTING_KEYS = 24;
    private static final int MAX_PROMPT_SUMMARY_CHARS = 80;

    private static final String EXTRACT_SYSTEM = """
            你是会话事实抽取器。只处理给定的本轮聊天原文，禁止编造。
            只抽取值得长期记住的结论：用户偏好、已做决定、日期时间、文件路径、外部对象 ID、任务目标。
            不抽取：寒暄、临时状态、你自己的不确定推测、与本轮话题无关的内容。
            只输出一个 JSON 对象（不要 Markdown、解释或代码块），字段必须为：
            {"facts": [{"factKey": "稳定的英文点分键（如 report.delivery.day）", \
            "factType": "下列枚举之一", "valueJson": "短 JSON 对象字符串", \
            "displaySummary": "不超过 80 字的中文摘要"}]}
            factType 只能是：USER_CONFIRMATION、APPROVAL、TASK_GOAL、DATE_TIME、ARTIFACT_PATH、\
            EXTERNAL_OBJECT、TOOL_OUTCOME、OPEN_QUESTION、DELIVERY_CONCLUSION。
            没有值得记录的内容时输出 {"facts": []}
            """;

    private final ChatMessageRecordRepository messages;
    private final ContextFactService facts;
    private final ModelRegistry registry;
    private final ModelGateway modelGateway;
    private final Executor executor;
    private final ChatProperties properties;
    private final ObjectMapper mapper;

    /** 同会话抽取单飞与节流标记；不持久化，重启后靠下一轮自然重推。 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastCompleted = new ConcurrentHashMap<>();

    public ContextFactExtractionService(ChatMessageRecordRepository messages, ContextFactService facts,
            ModelRegistry registry, ModelGateway modelGateway,
            @Qualifier("taskParallelExecutor") Executor executor, ChatProperties properties, ObjectMapper mapper) {
        this.messages = messages;
        this.facts = facts;
        this.registry = registry;
        this.modelGateway = modelGateway;
        this.executor = executor;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * 轮次收尾后异步触发（与摘要调度对称）。节流窗口内或已在抽取时直接跳过：
     * 抽取按轮尽力而为，被跳过的轮次不排队，由后续轮次自然重推。
     */
    public void scheduleTurn(Long userId, Long orgId, String sessionId, long fromSeq, long toSeq) {
        ChatProperties.Fact config = properties == null ? null : properties.fact();
        if (config == null || !config.enabled() || facts == null || fromSeq <= 0 || toSeq < fromSeq) {
            return;
        }
        String key = keyOf(userId, orgId, sessionId);
        Instant last = lastCompleted.get(key);
        if (last != null && last.plus(config.minInterval()).isAfter(Instant.now())) {
            return;
        }
        if (!running.add(key)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    extract(userId, orgId, sessionId, fromSeq, toSeq);
                } catch (RuntimeException e) {
                    log.warn("事实抽取后台任务异常：user={} session={} range={}:{}", userId, sessionId,
                            fromSeq, toSeq, e.getMessage());
                } finally {
                    running.remove(key);
                    lastCompleted.put(key, Instant.now());
                }
            });
        } catch (RejectedExecutionException e) {
            running.remove(key);
            log.warn("事实抽取调度被拒绝：user={} session={}", userId, sessionId);
        }
    }

    /** 会话清理时移除节流标记；已入库事实不受影响。 */
    public void cancel(Long userId, Long orgId, String sessionId) {
        String key = keyOf(userId, orgId, sessionId);
        running.remove(key);
        lastCompleted.remove(key);
    }

    /** 同步抽取一个轮次区间（供测试与运维补偿调用）；返回成功入库的候选数。 */
    public int extract(Long userId, Long orgId, String sessionId, long fromSeq, long toSeq) {
        ChatProperties.Fact config = properties == null ? null : properties.fact();
        if (config == null || !config.enabled()) {
            return 0;
        }
        RequestIdentity identity = new RequestIdentity(userId, orgId, "SYSTEM", "fact-extractor");
        List<ChatMessageRecord> rows = messages.findByOrgIdAndUserIdAndSessionIdAndSeqGreaterThanAndSeqLessThanEqualOrderBySeqAsc(
                orgId, userId, sessionId, fromSeq - 1, toSeq, PageRequest.of(0, MAX_TURN_MESSAGES));
        if (rows.isEmpty() || assistantChars(rows) < config.minAssistantChars()) {
            return 0;
        }

        String existingKeys = existingKeyPrompt(identity, sessionId);
        String content;
        try {
            content = invokeModel(identity, rows, existingKeys);
        } catch (RuntimeException e) {
            // 抽取失败不重试：下一轮模型自然重推，聊天不受影响。
            log.warn("事实抽取模型调用失败：user={} session={} range={}:{}", userId, sessionId, fromSeq, toSeq,
                    e.getMessage());
            return 0;
        }
        return captureCandidates(identity, sessionId, fromSeq, toSeq, content, config.maxFactsPerTurn());
    }

    private String invokeModel(RequestIdentity identity, List<ChatMessageRecord> rows, String existingKeys) {
        ModelSlot slot = registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER);
        if (slot == null) {
            return "";
        }
        UsageContext.set(new UsageContext.Snapshot(UsageContext.newRequestId(), "FACT_EXTRACT",
                identity.userId(), identity.orgId(), "fact-extractor", null, null, "DISABLED", 4_000, null, true));
        try {
            return modelGateway.call(new ModelInvocation(slot, EXTRACT_SYSTEM, List.of(),
                    buildPrompt(rows, existingKeys), ReasoningMode.DISABLED)).content();
        } finally {
            UsageContext.clear();
        }
    }

    private int captureCandidates(RequestIdentity identity, String sessionId, long fromSeq, long toSeq,
            String content, int maxFacts) {
        JsonNode candidates = parseFacts(content);
        if (candidates == null || !candidates.isArray()) {
            log.debug("事实抽取输出未包含候选：session={}", sessionId);
            return 0;
        }
        String evidence = evidenceRefs(sessionId, fromSeq, toSeq);
        int captured = 0;
        int skipped = 0;
        for (JsonNode candidate : candidates) {
            if (captured >= maxFacts) {
                break;
            }
            try {
                facts.capture(identity, toCaptureRequest(sessionId, candidate, evidence, toSeq));
                captured++;
            } catch (RuntimeException e) {
                // 候选级容错：非法 factKey/factType/valueJson 只跳过该条，不影响其余候选。
                skipped++;
                log.debug("事实抽取候选被拒绝：session={}：{}", sessionId, e.getMessage());
            }
        }
        if (captured > 0) {
            log.info("事实抽取入库：session={} range={}:{} captured={} skipped={}", sessionId,
                    fromSeq, toSeq, captured, skipped);
        }
        return captured;
    }

    /** 兼容模型偶发的 Markdown 围栏；解析失败返回 null，不抛出。 */
    private JsonNode parseFacts(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }
        try {
            JsonNode root = mapper.readTree(trimmed);
            return root == null ? null : root.get("facts");
        } catch (Exception e) {
            return null;
        }
    }

    private ContextFactService.CaptureRequest toCaptureRequest(String sessionId, JsonNode candidate,
            String evidence, long toSeq) {
        String factKey = textOf(candidate, "factKey");
        String valueJson = textOf(candidate, "valueJson");
        String displaySummary = textOf(candidate, "displaySummary");
        ContextFactType factType = ContextFactType.valueOf(textOf(candidate, "factType").trim());
        return new ContextFactService.CaptureRequest(ContextFactScopeType.CHAT_SESSION, sessionId, null, null,
                factKey, factType, valueJson, displaySummary, ContextFactTrustLevel.AGENT_DERIVED, null,
                ContextFactSourceType.CHAT_MESSAGE, sessionId, "turn:" + toSeq, null, evidence,
                Instant.now(), null, null);
    }

    /** 证据只存 seq 区间指针，不存原文；后续可按 span 回源取证（迭代 31B）。 */
    private String evidenceRefs(String sessionId, long fromSeq, long toSeq) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        ObjectNode span = array.addObject();
        span.put("type", "chat_span");
        span.put("sessionId", sessionId == null ? "" : sessionId.trim());
        span.put("fromSeq", fromSeq);
        span.put("toSeq", toSeq);
        return array.toString();
    }

    private String existingKeyPrompt(RequestIdentity identity, String sessionId) {
        try {
            List<ContextFactRecord> actives = facts.listActive(identity, ContextFactScopeType.CHAT_SESSION,
                    sessionId);
            if (actives.isEmpty()) {
                return "（无）";
            }
            StringBuilder builder = new StringBuilder();
            actives.stream().limit(MAX_EXISTING_KEYS).forEach(record -> builder.append("- ")
                    .append(record.getFactKey()).append(": ")
                    .append(bound(record.getDisplaySummary(), MAX_PROMPT_SUMMARY_CHARS)).append('\n'));
            return builder.toString();
        } catch (RuntimeException e) {
            return "（无）";
        }
    }

    private String buildPrompt(List<ChatMessageRecord> rows, String existingKeys) {
        StringBuilder builder = new StringBuilder("本轮对话原文：\n");
        for (ChatMessageRecord row : rows) {
            builder.append("USER".equals(row.getRole()) ? "USER: " : "ASSISTANT: ")
                    .append(bound(row.getContent(), MAX_MESSAGE_CHARS)).append('\n');
        }
        builder.append("已存在的事实键（不要重复抽取）：\n").append(existingKeys);
        return builder.toString();
    }

    private static int assistantChars(List<ChatMessageRecord> rows) {
        return rows.stream()
                .filter(row -> "ASSISTANT".equals(row.getRole()))
                .mapToInt(row -> row.getContent() == null ? 0 : row.getContent().length())
                .sum();
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private static String bound(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String keyOf(Long userId, Long orgId, String sessionId) {
        return "org:" + orgId + ":user:" + userId + ":session:" + sessionId;
    }
}
