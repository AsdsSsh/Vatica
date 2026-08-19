package com.example.vatica.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.runtime.AgentRuntime.AdvisoryKind;
import com.example.vatica.runtime.AgentRuntime.AdvisoryRequest;
import com.example.vatica.runtime.AgentRuntimeFactory;
import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.example.vatica.task.TaskVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Judge Agent（迭代 5.5 I5.5-1）：LLM-as-Judge 质量门禁——任务执行完给结果评分，
 * 低分触发自动返工（TaskService 编排），是"干得好不好"这层评测的载体。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>评分卡三维度</b>：完整性 30 + 正确性 50 + 格式 20 = 100，与"HITL 管能不能干、
 *       计划审批管怎么干"定位不重叠</li>
 *   <li><b>规则校验先行（不烧 token）</b>：步骤无结果/计划为空这类代码可判的事实问题，
 *       直接 FAIL 0 分，不调 LLM</li>
 *   <li><b>阈值在代码不在 prompt</b>：模型只出分数，PASS/FAIL 由 passThreshold 判定——
 *       调阈值只改配置，不换 prompt</li>
 *   <li><b>解析降级</b>：LLM 输出不可解析时，规则已通过则 PASS 兜底（score=null 如实标注），
 *       评测解析故障不阻断交付</li>
 *   <li>评测用<b>无工具 ChatClient</b>：评测只读材料、不执行任何操作，防副作用</li>
 * </ul>
 */
public class JudgeAgent {

    private static final Logger log = LoggerFactory.getLogger(JudgeAgent.class);

    public static final int MAX_SCORE = 100;

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

    private static final String SYSTEM_PROMPT = """
            你是 Vatica 质量评测 Agent（LLM-as-Judge）。根据任务目标与各步骤执行结果按评分卡打分，
            只输出一个 JSON 对象（不要 markdown 代码块、不要任何解释文字），格式：
            {"score":0,"completeness":0,"correctness":0,"format":0,"summary":"一句话评语","failStepIds":[1,2]}
            评分卡维度（三项满分合计 100）：
            1. 完整性（满分 30）：步骤是否全部完成、是否覆盖任务目标的全部要求；
            2. 正确性（满分 50）：数据是否来自工具返回、关键数字/时间/地点是否准确、有无编造幻觉；
            3. 格式（满分 20）：结果表述是否清晰、结构是否规范、交付物是否符合要求。
            铁律：
            1. score 必须等于三个维度得分之和；
            2. 只依据提供的材料评分，不得脑补材料中没有的信息；
            3. 步骤结果中如实报告"工具未执行/未配置"不算正确性错误（诚实报告是加分项），
               但若目标因此未完成，完整性/正确性应相应扣分；
            4. failStepIds 填写本次评测认定不合格的步骤编号列表（无不合格步骤时填 []）。""";

    /** 评测结果：score 可为 null（解析降级路径，规则已通过但 LLM 分数不可用）；failStepIds 供 Reflexion 反馈注入。 */
    public record Evaluation(Integer score, TaskVerdict verdict, String summary, List<Integer> failStepIds) {
        public Evaluation(Integer score, TaskVerdict verdict, String summary) {
            this(score, verdict, summary, List.of());
        }
    }

    /** 迭代 15 I15-6：结构化输出的评分卡（Spring AI entity 转换目标；schema 优先）。 */
    public static class JudgeScoreCard {
        private Integer score;
        private Integer completeness;
        private Integer correctness;
        private Integer format;
        private String summary;
        private List<Integer> failStepIds;

        public Integer getScore() { return score; }
        public void setScore(Integer score) { this.score = score; }
        public Integer getCompleteness() { return completeness; }
        public void setCompleteness(Integer completeness) { this.completeness = completeness; }
        public Integer getCorrectness() { return correctness; }
        public void setCorrectness(Integer correctness) { this.correctness = correctness; }
        public Integer getFormat() { return format; }
        public void setFormat(Integer format) { this.format = format; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<Integer> getFailStepIds() { return failStepIds; }
        public void setFailStepIds(List<Integer> failStepIds) { this.failStepIds = failStepIds; }
    }

    private final ChatClient judgeClient;
    private final ObjectMapper mapper;
    private final int passThreshold;
    private final AgentRuntimeFactory runtimeFactory;

    public JudgeAgent(ChatClient judgeClient, ObjectMapper mapper, int passThreshold) {
        this(judgeClient, mapper, passThreshold, null);
    }

    /** 迭代 20C：AgentScope 只给评分建议，阈值与 verdict 始终由本类决定。 */
    public JudgeAgent(ChatClient judgeClient, ObjectMapper mapper, int passThreshold,
            AgentRuntimeFactory runtimeFactory) {
        this.judgeClient = judgeClient;
        this.mapper = mapper;
        this.passThreshold = passThreshold;
        this.runtimeFactory = runtimeFactory;
    }

    /**
     * 评测：规则校验先行（硬失败不烧 token）→ LLM 评分卡 → 解析降级。
     */
    public Evaluation evaluate(String goal, TaskPlan plan) {
        return evaluate(goal, plan, judgeClient, null);
    }

    /** 迭代 13 I13-5：任务级临时/指定客户端评测。 */
    public Evaluation evaluate(String goal, TaskPlan plan, ChatClient client) {
        return evaluate(goal, plan, client, null);
    }

    /** 迭代 20C：临时凭据显式传槽位；Legacy 仍使用传入的 Spring AI 客户端。 */
    public Evaluation evaluate(String goal, TaskPlan plan, ChatClient client, ModelSlot modelSlot) {
        Evaluation ruleFail = ruleCheck(plan);
        if (ruleFail != null) {
            return ruleFail;
        }
        String advisedRaw = advisoryRaw(goal, plan, modelSlot);
        if (advisedRaw != null) {
            Evaluation advised = parse(advisedRaw);
            if (advised != null) {
                return advised;
            }
            log.warn("AgentScope 评测建议无法解析，降级为规则结果（PASS、无分数）。原始输出片段：{}",
                    snippet(advisedRaw));
            return new Evaluation(null, TaskVerdict.PASS,
                    "评测解析降级：规则校验通过，AgentScope 分数不可用", List.of());
        }
        // 迭代 15 I15-6：结构化输出 schema 优先；供应商不支持时回退正则解析
        Evaluation structured = evaluateStructured(goal, plan, client);
        if (structured != null) {
            return structured;
        }
        String raw = client.prompt().system(SYSTEM_PROMPT).user(userPrompt(goal, plan)).call().content();
        Evaluation eval = parse(raw);
        if (eval != null) {
            return eval;
        }
        log.warn("评测输出无法解析，降级为规则结果（PASS、无分数）。原始输出片段：{}", snippet(raw));
        return new Evaluation(null, TaskVerdict.PASS, "评测解析降级：规则校验通过，LLM 评分不可用", List.of());
    }

    private String advisoryRaw(String goal, TaskPlan plan, ModelSlot modelSlot) {
        RequestIdentity identity = RequestIdentityContext.current();
        if (runtimeFactory == null || identity == null) {
            return null;
        }
        return runtimeFactory.advise(new AdvisoryRequest(AdvisoryKind.JUDGE, SYSTEM_PROMPT,
                userPrompt(goal, plan), identity, modelSlot, "judge-" + UUID.randomUUID()))
                .map(result -> result.content())
                .orElse(null);
    }

    private Evaluation evaluateStructured(String goal, TaskPlan plan, ChatClient client) {
        try {
            JudgeScoreCard card = client.prompt().system(SYSTEM_PROMPT).user(userPrompt(goal, plan))
                    .call().entity(JudgeScoreCard.class);
            if (card == null) {
                return null;
            }
            Integer score = normalizedScore(card.getScore(), card.getCompleteness(),
                    card.getCorrectness(), card.getFormat());
            if (score == null) {
                return null;
            }
            String summary = card.getSummary() == null || card.getSummary().isBlank()
                    ? "（无评语）" : card.getSummary();
            return new Evaluation(score, score >= passThreshold ? TaskVerdict.PASS : TaskVerdict.FAIL,
                    summary, card.getFailStepIds() == null ? List.of() : List.copyOf(card.getFailStepIds()));
        } catch (Exception e) {
            log.info("评测结构化输出不可用，回退正则解析：{}", e.getMessage());
            return null;
        }
    }

    /** 规则校验（代码先行）：计划为空 / 步骤无结果 → 硬失败，不调 LLM。 */
    private static Evaluation ruleCheck(TaskPlan plan) {
        if (plan.getSteps().isEmpty()) {
            return new Evaluation(0, TaskVerdict.FAIL, "计划无步骤，无法评测（规则校验）", List.of());
        }
        for (TaskStep step : plan.getSteps()) {
            if (step.getResult() == null || step.getResult().isBlank()) {
                return new Evaluation(0, TaskVerdict.FAIL,
                        "步骤 " + step.getId() + " 无执行结果（规则校验）", List.of(step.getId()));
            }
        }
        return null;
    }

    private static String userPrompt(String goal, TaskPlan plan) {
        StringBuilder sb = new StringBuilder("任务目标：").append(goal).append("\n\n各步骤执行结果：\n");
        for (TaskStep step : plan.getSteps()) {
            sb.append("【步骤 ").append(step.getId()).append("】").append(step.getDescription())
                    .append("\n执行结果：").append(evidence(step.getResult())).append("\n\n");
        }
        return sb.append("请按评分卡对以上执行结果评分。").toString();
    }

    /** 迭代 15 I15-12：Judge 证据压缩——单步骤结果上限 4000 字符，关键数据优先（保留前部）。 */
    private static String evidence(String result) {
        if (result == null || result.length() <= 4_000) {
            return result;
        }
        return result.substring(0, 4_000) + "\n…（步骤结果过长，已截断）";
    }

    /** 解析模型输出：剥 markdown 围栏后取 JSON；score 缺失时用三维度之和兜底；越界收敛到 0-100。 */
    Evaluation parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = JSON_BLOCK.matcher(raw);
        if (!m.find()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(m.group());
            Integer score = normalizedScore(intOrNull(node.get("score")),
                    intOrNull(node.get("completeness")), intOrNull(node.get("correctness")),
                    intOrNull(node.get("format")));
            if (score == null) {
                return null;
            }
            String summary = node.hasNonNull("summary") ? node.get("summary").asText() : "（无评语）";
            List<Integer> failStepIds = parseFailStepIds(node.get("failStepIds"));
            return new Evaluation(score, score >= passThreshold ? TaskVerdict.PASS : TaskVerdict.FAIL,
                    summary, failStepIds);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Integer> parseFailStepIds(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonNode child : node) {
            if (child.canConvertToInt()) {
                ids.add(child.intValue());
            }
        }
        return List.copyOf(ids);
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.intValue() : null;
    }

    /** 任一维度存在时按本地满分边界重算总分，拒绝模型提交互相矛盾的 score。 */
    private static Integer normalizedScore(Integer score, Integer completeness,
            Integer correctness, Integer format) {
        if (completeness != null || correctness != null || format != null) {
            return clamp(completeness, 30) + clamp(correctness, 50) + clamp(format, 20);
        }
        return score == null ? null : Math.max(0, Math.min(MAX_SCORE, score));
    }

    private static int clamp(Integer value, int max) {
        return value == null ? 0 : Math.max(0, Math.min(max, value));
    }

    private static String snippet(String raw) {
        return raw == null ? "<null>" : raw.substring(0, Math.min(120, raw.length())).replace('\n', ' ');
    }
}
