package com.example.vatica.agent;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

import com.example.vatica.task.TaskPlan.TaskStep;
import com.example.vatica.trace.ReasoningContext;

/**
 * Executor Agent（迭代 5）：逐步执行计划步骤（带全部工具）。
 *
 * <p>上下文策略：目标 + 前序步骤结果摘要（滑动上下文，不无限膨胀）→ 当前步骤指令；
 * 幻觉控制延续：system prompt 要求"数据必须来自工具返回，不得编造"。
 */
public class ExecutorAgent {

    private static final String SYSTEM_PROMPT = """
            你是 Vatica 执行 Agent。按指示执行任务步骤，需要数据时调用工具获取。
            铁律：只使用工具返回的数据，工具返回中没有的数据一律不得编造。
            文件权限规则：用户或计划指定了具体路径时，直接调用对应文件工具；
            未授权目录会自动触发用户授权弹窗（等待期间不要重复调用同一工具）；
            只有工具明确返回"用户拒绝授权"时，才在总结里说明被拒绝原因并建议替代路径；
            永远不要指导用户去"文件权限设置"手动添加授权目录。
            工具执行失败时，把失败原因如实写进总结，不要假装成功。""";

    private final ChatClient executorClient;

    public ExecutorAgent(ChatClient executorClient) {
        this.executorClient = executorClient;
    }

    /**
     * 执行单个步骤，返回结果摘要。
     *
     * @param goal 任务目标（全步骤共享上下文）
     * @param step 当前步骤
     * @param previousResults 前序步骤结果摘要（可为空）
     */
    public String executeStep(String goal, TaskStep step, List<String> previousResults) {
        return executeStep(goal, step, previousResults, null);
    }

    /**
     * 执行单个步骤，返回结果摘要。
     *
     * @param toolCallbacks 迭代 11：绑定了本任务权限快照的工具回调；null 时用默认工具
     */
    public String executeStep(String goal, TaskStep step, List<String> previousResults,
            ToolCallback[] toolCallbacks) {
        return executeStep(goal, step, previousResults, toolCallbacks, executorClient);
    }

    /** 迭代 13 I13-5：任务级临时/指定客户端执行。 */
    public String executeStep(String goal, TaskStep step, List<String> previousResults,
            ToolCallback[] toolCallbacks, ChatClient client) {
        return executeStep(goal, step, previousResults, toolCallbacks, client, null);
    }

    /**
     * 迭代 15 I15-2 Reflexion：执行时注入上一轮 Judge 反馈，本轮必须针对性修复；
     * 反馈只纠错，不改变任务目标或扩大范围。
     */
    public String executeStep(String goal, TaskStep step, List<String> previousResults,
            ToolCallback[] toolCallbacks, ChatClient client, String reflectionFeedback) {
        var prompt = client.prompt()
                .system(SYSTEM_PROMPT)
                .user("任务目标：" + goal);
        if (previousResults != null && !previousResults.isEmpty()) {
            StringBuilder ctx = new StringBuilder("依赖步骤结果与任务笔记（参考，不要重复执行）：\n");
            for (int i = 0; i < previousResults.size(); i++) {
                ctx.append(i + 1).append(". ").append(previousResults.get(i)).append('\n');
            }
            prompt = prompt.user(ctx.toString());
        }
        if (reflectionFeedback != null && !reflectionFeedback.isBlank()) {
            prompt = prompt.user("上一轮质量评测反馈：\n" + reflectionFeedback
                    + "\n本轮必须针对性修复；只使用工具返回的数据；不得改变任务目标或扩大任务范围。");
        }
        if (toolCallbacks != null && toolCallbacks.length > 0) {
            prompt = prompt.toolCallbacks(toolCallbacks);
        }
        var callSpec = prompt.user("现在执行步骤（第 " + step.getId() + " 步）：" + step.getDescription()
                + "\n完成后用一句话总结本步骤结果（含关键数据）。").call();
        // 迭代 15 I15-7：优先 chatResponse 取完整响应（含 reasoning 元数据）；mock/异常路径回退 content()
        ChatResponse response = null;
        try {
            response = callSpec.chatResponse();
        } catch (RuntimeException e) {
            // 供应商/测试环境不支持时走纯文本路径
        }
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            ReasoningContext.set(reasoningOf(response));
            return response.getResult().getOutput().getText();
        }
        return callSpec.content();
    }

    private static String reasoningOf(ChatResponse response) {
        for (Generation generation : response.getResults()) {
            if (generation.getOutput() != null) {
                Object value = generation.getOutput().getMetadata().get("reasoningContent");
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        }
        if (response.getMetadata() != null) {
            Object value = response.getMetadata().get("reasoningContent");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
