package com.example.vatica.agent;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;

import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Planner Agent（迭代 5 I5-1）：把用户目标拆解为带审批标记的步骤计划。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>规划用无工具 ChatClient</b>：规划阶段只做分解，不执行——避免模型在规划时顺手调工具产生副作用</li>
 *   <li><b>结构化输出 + 降级</b>：system prompt 要求只输出 JSON；解析失败（围栏/噪声/幻觉字段）
 *       降级为"单步计划"并如实标注——规划失败不阻断任务创建，执行阶段仍可推进</li>
 *   <li><b>审批点由规划器标记</b>：涉及发邮件/覆盖文件等不可逆操作的步骤 needsApproval=true，
 *       执行流据此挂起（HITL 入口）</li>
 *   <li>步骤数上限 8（防规划器输出超长计划）；输出步骤重编号 1..n（不信任模型编号）</li>
 * </ul>
 */
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    /** 计划步骤数上限（护栏）。 */
    public static final int MAX_STEPS = 8;

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

    private static final String SYSTEM_PROMPT = """
            你是任务规划 Agent。把用户目标拆解为可执行的步骤，只输出一个 JSON 对象（不要 markdown 代码块、不要任何解释文字），格式：
            {"steps":[{"description":"步骤描述：具体、可执行、写明要调用哪个工具","needsApproval":false}]}
            规则：
            1. 步骤 1-8 个，按执行顺序排列；
            2. 涉及发送邮件、覆盖用户已有文件、删除数据等不可逆操作的步骤，needsApproval 必须为 true，其余为 false；
            3. 涉及具体时间/地点/数字的步骤，描述里注明"数据必须来自工具返回，不得编造"；
            4. 不要发明不存在的工具，只用系统提供的工具（read_file/write_file/list_files/calculator/text_stats/create_word_report/create_excel_stats/calendar_*/todo_*/mail_*）。""";

    private final ChatClient plannerClient;
    private final ObjectMapper mapper;

    public PlannerAgent(ChatClient plannerClient, ObjectMapper mapper) {
        this.plannerClient = plannerClient;
        this.mapper = mapper;
    }

    /**
     * 规划：返回步骤计划（解析失败降级为单步计划）。
     */
    public TaskPlan plan(String goal) {
        String raw = plannerClient.prompt().system(SYSTEM_PROMPT).user(goal).call().content();
        TaskPlan plan = parse(raw);
        if (plan == null) {
            log.warn("规划输出无法解析，降级为单步计划。原始输出片段：{}", snippet(raw));
            plan = fallbackPlan(goal);
        }
        return normalize(plan);
    }

    /** 解析模型输出：剥 markdown 围栏后取第一个完整 JSON 对象。 */
    TaskPlan parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = JSON_BLOCK.matcher(raw);
        if (!m.find()) {
            return null;
        }
        try {
            TaskPlan plan = mapper.readValue(m.group(), TaskPlan.class);
            return plan.getSteps() == null || plan.getSteps().isEmpty() ? null : plan;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static TaskPlan fallbackPlan(String goal) {
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(new TaskStep(1, "执行目标：" + goal, false)));
        return plan;
    }

    /** 归一化：重编号、截断超长计划、清空结果字段。 */
    private static TaskPlan normalize(TaskPlan plan) {
        List<TaskStep> steps = plan.getSteps();
        if (steps.size() > MAX_STEPS) {
            steps = steps.subList(0, MAX_STEPS);
        }
        int i = 1;
        for (TaskStep step : steps) {
            step.setId(i++);
            step.setResult(null);
        }
        plan.setSteps(steps);
        return plan;
    }

    private static String snippet(String raw) {
        return raw == null ? "<null>" : raw.substring(0, Math.min(120, raw.length())).replace('\n', ' ');
    }
}
