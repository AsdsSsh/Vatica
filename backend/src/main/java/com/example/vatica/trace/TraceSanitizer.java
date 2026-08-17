package com.example.vatica.trace;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * trace 脱敏（迭代 15 I15-1）：工具参数/结果进入 SSE 与 agent_trace 前统一处理——
 * JSON 字段名命中敏感词的值一律替换为 ***；长文本只保留摘要（输入上限 400 字符，
 * 输出保留头 200 + 尾 80），完整内容永不落 trace。
 */
public final class TraceSanitizer {

    /** 输入摘要上限（脱敏后的字符串）。 */
    public static final int MAX_INPUT_CHARS = 400;
    /** 输出摘要：头部保留字符数。 */
    public static final int OUTPUT_HEAD_CHARS = 200;
    /** 输出摘要：尾部保留字符数。 */
    public static final int OUTPUT_TAIL_CHARS = 80;
    /** 头部 + 尾部超过该长度才截断。 */
    public static final int OUTPUT_TRUNCATE_THRESHOLD = OUTPUT_HEAD_CHARS + OUTPUT_TAIL_CHARS;

    private static final List<String> SENSITIVE_FIELDS = List.of(
            "apikey", "api_key", "password", "passwd", "secret", "token", "authorization", "code");
    private static final Pattern RAW_SECRET_PATTERN = Pattern.compile(
            "(?i)(api[-_]?key|password|passwd|secret|token|authorization)\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|[^,\\s}\\]]+)");

    private TraceSanitizer() {
    }

    /** 工具输入 → 脱敏摘要。 */
    public static String inputSummary(ObjectMapper mapper, String raw) {
        return truncate(sanitize(mapper, raw), MAX_INPUT_CHARS, "…（输入已截断）");
    }

    /** 工具输出 → 头尾摘要（不落全文）。 */
    public static String outputSummary(String raw, StringBuilder lengthHolder) {
        String value = raw == null ? "" : raw;
        if (lengthHolder != null) {
            lengthHolder.append(value.length());
        }
        if (value.length() <= OUTPUT_TRUNCATE_THRESHOLD) {
            return value;
        }
        return value.substring(0, OUTPUT_HEAD_CHARS)
                + "\n…（输出已截断，共 " + value.length() + " 字符）…\n"
                + value.substring(value.length() - OUTPUT_TAIL_CHARS);
    }

    /** 递归脱敏 JSON 字段；非 JSON 输入按 key=value 形态的敏感键脱敏。 */
    public static String sanitize(ObjectMapper mapper, String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return RAW_SECRET_PATTERN.matcher(trimmed).replaceAll("$1: ***");
        }
        try {
            JsonNode root = mapper.readTree(trimmed);
            return mapper.writeValueAsString(mask(root, mapper));
        } catch (Exception e) {
            // 不是合法 JSON（或序列化失败）：退回 key=value 规则，不阻断业务
            return RAW_SECRET_PATTERN.matcher(trimmed).replaceAll("$1: ***");
        }
    }

    private static JsonNode mask(JsonNode node, ObjectMapper mapper) {
        if (node instanceof ObjectNode object) {
            ObjectNode masked = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (isSensitive(key)) {
                    masked.put(key, "***");
                } else {
                    masked.set(key, mask(field.getValue(), mapper));
                }
            }
            return masked;
        }
        if (node instanceof ArrayNode array) {
            List<JsonNode> masked = new ArrayList<>();
            for (JsonNode child : array) {
                masked.add(mask(child, mapper));
            }
            ArrayNode result = mapper.createArrayNode();
            result.addAll(masked);
            return result;
        }
        return node;
    }

    private static boolean isSensitive(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT).replace('-', '_').trim();
        return SENSITIVE_FIELDS.contains(normalized)
                || normalized.endsWith(".code")
                || normalized.startsWith("code.");
    }

    private static String truncate(String value, int max, String marker) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + marker;
    }
}
