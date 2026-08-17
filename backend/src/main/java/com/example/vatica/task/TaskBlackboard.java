package com.example.vatica.task;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import com.example.vatica.config.ModelRegistry;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextTrimmer;
import com.example.vatica.context.TokenEstimator;
import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 迭代 15 I15-11：任务黑板——每步只注入 dependsOn 依赖步骤摘要 + 全局滚动笔记，
 * 替代“全部前序结果无限拼接”。压缩同步执行：步骤结果超 400 字生成 ≤200 字 digest；
 * 每波完成后若 digest 总量超阈值，把旧笔记 + 新 digest 合并为 ≤800 字滚动笔记并推进水位线。
 * 失败降级（digest=null、水位不动、任务继续）。
 */
public class TaskBlackboard {

    private static final Logger log = LoggerFactory.getLogger(TaskBlackboard.class);
    public static final int DIGEST_THRESHOLD_CHARS = 400;
    public static final int DIGEST_MAX_CHARS = 200;
    public static final int FALLBACK_CHARS = 500;
    public static final int MERGE_THRESHOLD_CHARS = 1_200;
    public static final int NOTES_MAX_CHARS = 800;

    private static final String DIGEST_SYSTEM = """
            你是任务结果摘要 Agent。把执行结果压缩成不超过 200 字的关键事实摘要，
            保留数字、时间、路径、结论；禁止编造与评价。""";
    private static final String NOTES_SYSTEM = """
            你是任务黑板笔记 Agent。把旧笔记与新的步骤摘要合并成不超过 800 字的滚动笔记，
            保留仍会影响后续步骤的目标、决定、关键数字与路径；禁止编造与评价。""";

    private final ModelRegistry registry;
    private final ContextBudget budget;

    public TaskBlackboard(ModelRegistry registry, ContextBudget budget) {
        this.registry = registry;
        this.budget = budget;
    }

    /** 单步上下文 = 滚动笔记（当前步依赖水位线之前步骤时）+ 依赖步骤 digest；按 executor 预算兜底裁剪。 */
    public List<String> contextFor(String goal, TaskPlan plan, TaskStep step) {
        List<Integer> dependencies = effectiveDependencies(step);
        List<Message> messages = new ArrayList<>();
        if (plan.getGlobalNotes() != null && !plan.getGlobalNotes().isBlank()
                && dependencies.stream().anyMatch(dep -> dep <= plan.getNoteThroughStepId())) {
            messages.add(new UserMessage("任务滚动笔记：\n" + plan.getGlobalNotes()));
        }
        for (Integer dep : dependencies) {
            TaskStep dependency = findStep(plan, dep);
            String digest = dependency.getResultDigest();
            if (digest == null || digest.isBlank()) {
                String result = dependency.getResult();
                digest = result == null ? "" : truncate(result, FALLBACK_CHARS);
            }
            messages.add(new UserMessage("步骤 " + dep + " 摘要：" + digest));
        }
        List<Message> fitted = ContextTrimmer.trim(messages,
                budget.tokensFor(ContextBudget.CallSite.EXECUTOR), 1);
        List<String> context = new ArrayList<>(fitted.size());
        for (Message message : fitted) {
            context.add(message.getText());
        }
        return context;
    }

    /** 步骤完成后回填 result 并生成 digest（同步，避免异步写回竞态）；失败 digest=null 任务继续。 */
    public void recordStepResult(TaskPlan plan, TaskStep step, String result) {
        step.setResult(result);
        if (result == null || result.isBlank()) {
            step.setResultDigest(null);
            return;
        }
        if (result.length() <= DIGEST_THRESHOLD_CHARS) {
            step.setResultDigest(result);
            return;
        }
        try {
            String input = result.length() > 4_000 ? result.substring(0, 4_000) + "…" : result;
            String digest = registry.summarizerClient().prompt()
                    .system(DIGEST_SYSTEM)
                    .user("步骤结果：\n" + input)
                    .call().content();
            step.setResultDigest(digest == null || digest.isBlank()
                    ? truncate(result, DIGEST_MAX_CHARS) : truncate(digest, DIGEST_MAX_CHARS));
        } catch (Exception e) {
            log.warn("步骤 {} digest 生成失败，使用截断兜底", step.getId());
            step.setResultDigest(truncate(result, DIGEST_MAX_CHARS));
        }
    }

    /** 每波完成后合并滚动笔记；失败水位不动。 */
    public void mergeWaveNotes(TaskPlan plan) {
        int newThrough = plan.getSteps().stream()
                .filter(s -> s.getResult() != null && !s.getResult().isBlank())
                .mapToInt(TaskStep::getId)
                .max().orElse(0);
        if (newThrough <= plan.getNoteThroughStepId()) {
            return;
        }
        StringBuilder newDigests = new StringBuilder();
        for (TaskStep step : plan.getSteps()) {
            if (step.getId() <= plan.getNoteThroughStepId() || step.getId() > newThrough) {
                continue;
            }
            if (step.getResultDigest() != null && !step.getResultDigest().isBlank()) {
                newDigests.append(step.getId()).append(". ").append(step.getResultDigest()).append('\n');
            }
        }
        if (newDigests.isEmpty()) {
            plan.setNoteThroughStepId(newThrough);
            return;
        }
        int tokens = TokenEstimator.estimate(plan.getGlobalNotes()) + TokenEstimator.estimate(newDigests.toString());
        if (tokens * 2 < MERGE_THRESHOLD_CHARS) {   // 粗略：token*2 ≈ 字符量级，低于阈值直接追加不烧 LLM
            plan.setGlobalNotes(joinNotes(plan.getGlobalNotes(), newDigests.toString()));
            plan.setNoteThroughStepId(newThrough);
            return;
        }
        try {
            String oldNotes = plan.getGlobalNotes() == null ? "" : plan.getGlobalNotes();
            String merged = registry.summarizerClient().prompt()
                    .system(NOTES_SYSTEM)
                    .user("已有笔记：\n" + oldNotes + "\n\n新增步骤摘要：\n" + newDigests)
                    .call().content();
            if (merged != null && !merged.isBlank()) {
                plan.setGlobalNotes(truncate(merged, NOTES_MAX_CHARS));
                plan.setNoteThroughStepId(newThrough);
            }
        } catch (Exception e) {
            log.warn("任务滚动笔记合并失败，水位不动（下次波次自然重试）");
        }
    }

    private static List<Integer> effectiveDependencies(TaskStep step) {
        if (step.getDependsOn() == null) {
            return step.getId() <= 1 ? List.of() : List.of(step.getId() - 1);
        }
        return List.copyOf(step.getDependsOn());
    }

    private static TaskStep findStep(TaskPlan plan, int stepId) {
        return plan.getSteps().stream()
                .filter(s -> s.getId() == stepId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：计划中找不到步骤 id=" + stepId));
    }

    private static String joinNotes(String oldNotes, String digests) {
        String old = oldNotes == null || oldNotes.isBlank() ? "" : oldNotes + "\n";
        return truncate(old + digests, NOTES_MAX_CHARS);
    }

    private static String truncate(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
