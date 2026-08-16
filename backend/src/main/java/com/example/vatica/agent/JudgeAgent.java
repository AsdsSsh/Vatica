package com.example.vatica.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;

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
            {"score":0,"completeness":0,"correctness":0,"format":0,"summary":"一句话评语"}
            评分卡维度（三项满分合计 100）：
            1. 完整性（满分 30）：步骤是否全部完成、是否覆盖任务目标的全部要求；
            2. 正确性（满分 50）：数据是否来自工具返回、关键数字/时间/地点是否准确、有无编造幻觉；
            3. 格式（满分 20）：结果表述是否清晰、结构是否规范、交付物是否符合要求。
            铁律：
            1. score 必须等于三个维度得分之和；
            2. 只依据提供的材料评分，不得脑补材料中没有的信息；
            3. 步骤结果中如实报告"工具未执行/未配置"不算正确性错误（诚实报告是加分项），
               但若目标因此未完成，完整性/正确性应相应扣分。""";

    /** 评测结果：score 可为 null（解析降级路径，规则已通过但 LLM 分数不可用）。 */
    public record Evaluation(Integer score, TaskVerdict verdict, String summary) {
    }

    private final ChatClient judgeClient;
    private final ObjectMapper mapper;
    private final int passThreshold;

    public JudgeAgent(ChatClient judgeClient, ObjectMapper mapper, int passThreshold) {
        this.judgeClient = judgeClient;
        this.mapper = mapper;
        this.passThreshold = passThreshold;
    }

    /**
     * 评测：规则校验先行（硬失败不烧 token）→ LLM 评分卡 → 解析降级。
     */
    public Evaluation evaluate(String goal, TaskPlan plan) {
        return evaluate(goal, plan, judgeClient);
    }

    /** 迭代 13 I13-5：任务级临时/指定客户端评测。 */
    public Evaluation evaluate(String goal, TaskPlan plan, ChatClient client) {
        Evaluation ruleFail = ruleCheck(plan);
        if (ruleFail != null) {
            return ruleFail;
        }
        String raw = client.prompt().system(SYSTEM_PROMPT).user(userPrompt(goal, plan)).call().content();
        Evaluation eval = parse(raw);
        if (eval != null) {
            return eval;
        }
        log.warn("评测输出无法解析，降级为规则结果（PASS、无分数）。原始输出片段：{}", snippet(raw));
        return new Evaluation(null, TaskVerdict.PASS, "评测解析降级：规则校验通过，LLM 评分不可用");
    }

    /** 规则校验（代码先行）：计划为空 / 步骤无结果 → 硬失败，不调 LLM。 */
    private static Evaluation ruleCheck(TaskPlan plan) {
        if (plan.getSteps().isEmpty()) {
            return new Evaluation(0, TaskVerdict.FAIL, "计划无步骤，无法评测（规则校验）");
        }
        for (TaskStep step : plan.getSteps()) {
            if (step.getResult() == null || step.getResult().isBlank()) {
                return new Evaluation(0, TaskVerdict.FAIL, "步骤 " + step.getId() + " 无执行结果（规则校验）");
            }
        }
        return null;
    }

    private static String userPrompt(String goal, TaskPlan plan) {
        StringBuilder sb = new StringBuilder("任务目标：").append(goal).append("\n\n各步骤执行结果：\n");
        for (TaskStep step : plan.getSteps()) {
            sb.append("【步骤 ").append(step.getId()).append("】").append(step.getDescription())
                    .append("\n执行结果：").append(step.getResult()).append("\n\n");
        }
        return sb.append("请按评分卡对以上执行结果评分。").toString();
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
            Integer score = intOrNull(node.get("score"));
            if (score == null) {
                int dims = intOrZero(node.get("completeness")) + intOrZero(node.get("correctness"))
                        + intOrZero(node.get("format"));
                score = (dims > 0 || node.has("completeness") || node.has("correctness") || node.has("format"))
                        ? dims
                        : null;
            }
            if (score == null) {
                return null;
            }
            score = Math.max(0, Math.min(MAX_SCORE, score));
            String summary = node.hasNonNull("summary") ? node.get("summary").asText() : "（无评语）";
            return new Evaluation(score, score >= passThreshold ? TaskVerdict.PASS : TaskVerdict.FAIL, summary);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.intValue() : null;
    }

    private static int intOrZero(JsonNode node) {
        Integer v = intOrNull(node);
        return v == null ? 0 : v;
    }

    private static String snippet(String raw) {
        return raw == null ? "<null>" : raw.substring(0, Math.min(120, raw.length())).replace('\n', ' ');
    }
}
