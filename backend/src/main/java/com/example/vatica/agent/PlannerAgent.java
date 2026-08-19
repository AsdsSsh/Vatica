package com.example.vatica.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import com.example.vatica.task.ReflectionFeedback;
import com.example.vatica.task.BlackboardEntry;
import com.example.vatica.task.CollaborationDecision;
import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.example.vatica.runtime.AgentRegistry;
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
            {"steps":[{"description":"步骤描述：具体、可执行、写明要调用哪个工具","agent":"workspace","needsApproval":false,"dependsOn":[],"writeResources":[]}]}
            规则：
            1. 步骤 1-8 个，按执行顺序排列；
            2. 涉及发送邮件、覆盖用户已有文件、删除数据等不可逆操作的步骤，needsApproval 必须为 true，其余为 false；
            3. 涉及具体时间/地点/数字的步骤，描述里注明"数据必须来自工具返回，不得编造"；
            4. 没有先后依赖的步骤声明可并行：dependsOn 填依赖的前序步骤编号列表（从 1 开始、只能引用编号更小的步骤）；
               完全独立的步骤填 []（例如两个互不依赖的查询步骤都写 []）；省略该字段 = 默认依赖上一步（顺序执行）；
            5. 每个步骤必须从"当前可用角色"中选择 agent；一个步骤需要跨角色工具时拆成有依赖关系的多个步骤；
            6. 不要发明不存在的工具，只使用"当前可用工具"清单里的工具；
            7. 涉及用户指定的具体文件路径时，步骤描述里直接使用该路径（先 list_files 确认存在性也是可以的）；
               未授权目录会在执行时自动触发用户授权弹窗，因此**永远不要要求用户手动添加授权目录或修改权限设置**；
            8. 基于知识库生成文档时，先安排 research 调用 search_knowledge_base 并保留引用，
               再安排依赖该步骤的 document 生成文档。""";

    private static final String REVISE_SYSTEM_PROMPT = """
            你是任务规划 Agent。上一轮计划执行后质量评测不合格，请根据反馈修订计划，只输出一个 JSON 对象
            （不要 markdown 代码块、不要任何解释文字），格式：
            {"steps":[{"description":"步骤描述：具体、可执行、写明要调用哪个工具","agent":"workspace","needsApproval":false,"dependsOn":[],"writeResources":[]}]}
            修订规则：
            1. 只针对反馈中失败的步骤改进：换更合适的工具、补充校验步骤、明确数据来源或拆分过大的步骤；
            2. 仍然正确的步骤可以保留，但输出必须包含完整步骤列表（不要省略）；
            3. 不得改变任务目标、不得扩大任务范围、不得新增用户没要求的工作；
            4. 步骤 1-8 个；不可逆操作 needsApproval=true；dependsOn 规则与首次规划一致；
            5. 每步必须选择当前可用角色之一；只使用系统提供的工具，不要发明不存在的工具。""";

    private final ChatClient plannerClient;
    private final ObjectMapper mapper;
    private final ToolCallbackProvider toolProvider;
    private final AgentRegistry agentRegistry;

    public PlannerAgent(ChatClient plannerClient, ObjectMapper mapper) {
        this(plannerClient, mapper, null, new AgentRegistry());
    }

    /** 迭代 15 I15-12：工具清单从 ToolCallbackProvider 动态生成，防系统提示与注册工具漂移。 */
    public PlannerAgent(ChatClient plannerClient, ObjectMapper mapper, ToolCallbackProvider toolProvider) {
        this(plannerClient, mapper, toolProvider, new AgentRegistry());
    }

    /** 迭代 17A：角色清单来自 AgentRegistry，模型输出统一做合法化回退。 */
    public PlannerAgent(ChatClient plannerClient, ObjectMapper mapper, ToolCallbackProvider toolProvider,
            AgentRegistry agentRegistry) {
        this.plannerClient = plannerClient;
        this.mapper = mapper;
        this.toolProvider = toolProvider;
        this.agentRegistry = agentRegistry;
    }

    /**
     * 规划：返回步骤计划（解析失败降级为单步计划）。
     */
    public TaskPlan plan(String goal) {
        return plan(goal, plannerClient);
    }

    /** 迭代 13 I13-5：任务级临时/指定客户端规划（平台默认仍走注入客户端）。 */
    public TaskPlan plan(String goal, ChatClient client) {
        // 迭代 15 I15-6：结构化输出 schema 优先；供应商不支持/解析失败时回退正则降级
        String system = systemPrompt();
        TaskPlan structured = structuredPlan(client, system, goal);
        if (structured != null && !structured.getSteps().isEmpty()) {
            return normalize(structured);
        }
        String raw = client.prompt().system(system).user(goal).call().content();
        TaskPlan plan = parse(raw);
        if (plan == null) {
            log.warn("规划输出无法解析，降级为单步计划。原始输出片段：{}", snippet(raw));
            plan = fallbackPlan(goal);
        }
        return normalize(plan);
    }

    /**
     * 迭代 15 I15-2 Reflexion：Judge FAIL 后限 1 次重规划。
     * 输入原始目标 + 上一轮计划（含失败记录）+ Judge 反馈，输出同格式 JSON；
     * 解析失败返回上一轮计划本身（回退旧计划，不改变目标/不扩大范围）。
     */
    public TaskPlan revise(String goal, TaskPlan previous, ReflectionFeedback feedback) {
        String revisePrompt = REVISE_SYSTEM_PROMPT + roleListSuffix() + toolListSuffix();
        TaskPlan structured = structuredPlan(plannerClient, revisePrompt,
                reviseUserPrompt(goal, previous, feedback));
        if (structured != null && !structured.getSteps().isEmpty()) {
            return normalize(structured);
        }
        String raw = plannerClient.prompt().system(revisePrompt)
                .user(reviseUserPrompt(goal, previous, feedback))
                .call().content();
        TaskPlan revised = parse(raw);
        if (revised == null) {
            log.warn("重规划输出无法解析，回退旧计划。原始输出片段：{}", snippet(raw));
            return previous;
        }
        return normalize(revised);
    }

    /**
     * 迭代 17B：运行中协作裁决。Planner 只能修改未完成步骤或提出补步，
     * 不能直接改业务状态；TaskBlackboard 负责机械校验和预算。
     */
    public CollaborationDecision resolveCollaboration(String goal, TaskPlan plan,
            List<BlackboardEntry> signals, int maxDiscoveries) {
        return resolveCollaboration(goal, plan, signals, maxDiscoveries, plannerClient);
    }

    /** 请求级模型凭据版本，EPHEMERAL 任务不会在协作重规划时意外切回平台模型。 */
    public CollaborationDecision resolveCollaboration(String goal, TaskPlan plan,
            List<BlackboardEntry> signals, int maxDiscoveries, ChatClient client) {
        String system = """
                你是 Vatica 协作 Planner。任务已经开始执行，收到 Worker 的 need-help 或 conflict 信号。
                只在未完成步骤范围内做最小调整，不得改变原始目标，不得删除已完成步骤，不得引入自由对话。
                冲突优先通过增加 dependsOn 串行化或改写当前步骤解决；无法可靠裁决时 resolved=false 交给人工。
                最多提出 %d 个 discovery，所有补步仍需遵守审批规则。
                只输出 JSON：
                {"resolved":true,"summary":"裁决说明","patches":[{"stepId":2,"description":"改派后的步骤",
                "agent":"workspace","needsApproval":false,"dependsOn":[1],"writeResources":[]}],
                "discoveries":[]}
                """.formatted(Math.max(0, maxDiscoveries)) + roleListSuffix() + toolListSuffix();
        String user = collaborationUserPrompt(goal, plan, signals);
        try {
            CollaborationDecision structured = client.prompt().system(system).user(user)
                    .call().entity(CollaborationDecision.class);
            if (structured != null) {
                return structured;
            }
        } catch (Exception e) {
            log.info("协作裁决结构化输出不可用，回退 JSON 文本：{}", e.getMessage());
        }
        try {
            String raw = client.prompt().system(system).user(user).call().content();
            Matcher matcher = JSON_BLOCK.matcher(raw == null ? "" : raw);
            if (matcher.find()) {
                return mapper.readValue(matcher.group(), CollaborationDecision.class);
            }
        } catch (Exception e) {
            log.warn("协作裁决输出无法解析，升级人工：{}", e.getMessage());
        }
        return CollaborationDecision.unresolved("Planner 无法可靠裁决，请人工仲裁。");
    }

    /** 迭代 15 I15-12：系统提示 + 动态工具清单（provider 缺失时回退已知本地工具名，测试/降级友好）。 */
    private String systemPrompt() {
        return SYSTEM_PROMPT + roleListSuffix() + toolListSuffix();
    }

    private String roleListSuffix() {
        return "\n当前可用角色：" + agentRegistry.plannerPrompt() + "。";
    }

    private String toolListSuffix() {
        List<String> tools = new ArrayList<>();
        if (toolProvider != null) {
            try {
                for (ToolCallback callback : toolProvider.getToolCallbacks()) {
                    tools.add(callback.getToolDefinition().name());
                }
            } catch (Exception e) {
                log.warn("动态工具清单读取失败，回退静态清单", e);
            }
        }
        if (tools.isEmpty()) {
            tools = List.of("read_file", "write_file", "list_files", "calculator", "text_stats",
                    "create_word_report", "create_excel_stats", "calendar_*", "todo_*", "mail_*",
                    "list_workspace_roots", "search_knowledge_base");
        }
        return "\n当前可用工具：" + String.join(" / ", tools) + "。";
    }

    /** Spring AI 结构化输出（JSON schema 优先）；任何异常/空结果都返回 null，由调用方正则降级。 */
    private TaskPlan structuredPlan(ChatClient client, String system, String user) {        try {
            TaskPlan plan = client.prompt().system(system).user(user).call().entity(TaskPlan.class);
            return plan == null || plan.getSteps() == null ? null : plan;
        } catch (Exception e) {
            log.info("结构化输出不可用，回退正则解析：{}", e.getMessage());
            return null;
        }
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

    /** 归一化：重编号、截断超长计划、清空结果字段、解析依赖（迭代 6）。 */
    private TaskPlan normalize(TaskPlan plan) {
        List<TaskStep> steps = plan.getSteps();
        if (steps.size() > MAX_STEPS) {
            steps = steps.subList(0, MAX_STEPS);
        }
        int i = 1;
        for (TaskStep step : steps) {
            step.setId(i);
            step.setResult(null);
            step.setResultDigest(null);
            step.setAgent(agentRegistry.normalizeId(step.getAgent()));
            step.setWriteResources(normalizeWriteResources(step.getWriteResources()));
            step.setDependsOn(normalizeDependencies(step.getDependsOn(), i));
            i++;
        }
        plan.setSteps(steps);
        return plan;
    }

    /**
     * 依赖归一化（迭代 6）：null（字段缺失）= 顺序执行（依赖上一步）；
     * 显式 [] = 与步骤 1 并行；显式声明则校验（去重、只允许引用编号更小的步骤），
     * 全部非法 → 保守退回顺序执行（模型声明了依赖却写错，不应授予并行）。
     */
    private static List<Integer> normalizeDependencies(List<Integer> raw, int stepId) {
        if (raw == null) {
            return stepId <= 1 ? List.of() : List.of(stepId - 1);
        }
        if (raw.isEmpty()) {
            return List.of();   // 显式 [] = 与步骤 1 并行
        }
        List<Integer> valid = new ArrayList<>();
        for (Integer dep : raw) {
            if (dep != null && dep >= 1 && dep < stepId && !valid.contains(dep)) {
                valid.add(dep);
            }
        }
        if (valid.isEmpty() && stepId > 1) {
            return List.of(stepId - 1);
        }
        return List.copyOf(valid);
    }

    private static List<String> normalizeWriteResources(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().replace('\\', '/').toLowerCase(java.util.Locale.ROOT))
                .distinct().limit(8).toList();
    }

    private static String reviseUserPrompt(String goal, TaskPlan previous, ReflectionFeedback feedback) {
        StringBuilder sb = new StringBuilder("原始任务目标：").append(goal).append("\n\n上一轮计划与执行结果：\n");
        for (TaskStep step : previous.getSteps()) {
            sb.append("【步骤 ").append(step.getId()).append("】").append(step.getDescription()).append('\n');
            sb.append("执行结果：").append(snippet(step.getResult())).append("\n\n");
        }
        sb.append("质量评测反馈：").append(feedback.summary())
                .append("（评分 ").append(feedback.score()).append(" 分）\n");
        if (feedback.failStepIds() != null && !feedback.failStepIds().isEmpty()) {
            sb.append("被认定不合格的步骤编号：").append(feedback.failStepIds()).append('\n');
        }
        return sb.append("请输出修订后的完整计划。").toString();
    }

    private static String collaborationUserPrompt(String goal, TaskPlan plan,
            List<BlackboardEntry> signals) {
        StringBuilder sb = new StringBuilder("原始任务目标：").append(goal).append("\n\n协作信号：\n");
        for (BlackboardEntry signal : signals) {
            sb.append("- ").append(signal.type()).append("，步骤 ").append(signal.relatedStepIds())
                    .append("，资源 ").append(signal.resource()).append("：").append(snippet(signal.content()))
                    .append('\n');
        }
        sb.append("\n当前计划（已完成步骤只能参考，不能修改）：\n");
        for (TaskPlan.TaskStep step : plan.getSteps()) {
            sb.append("步骤 ").append(step.getId()).append(" [")
                    .append(step.getResult() == null ? "未完成" : "已完成").append("] ")
                    .append(step.getDescription()).append("，agent=").append(step.getAgent())
                    .append("，dependsOn=").append(step.getDependsOn())
                    .append("，writeResources=").append(step.getWriteResources()).append('\n');
        }
        return sb.append("请给出最小、可审计的裁决。").toString();
    }

    private static String snippet(String raw) {
        if (raw == null) {
            return "<无结果>";
        }
        return raw.length() <= 800 ? raw : raw.substring(0, 800) + "…（结果过长已截断）";
    }
}
