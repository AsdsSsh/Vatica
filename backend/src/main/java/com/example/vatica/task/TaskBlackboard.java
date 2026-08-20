package com.example.vatica.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.ReasoningMode;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextTrimmer;
import com.example.vatica.context.TokenEstimator;
import com.example.vatica.model.ConversationMessage;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelInvocation;
import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.task.CollaborationDecision.StepPatch;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 任务黑板。迭代 15 提供依赖摘要与滚动笔记；迭代 17B 在同一 TaskPlan JSON 内增加
 * result/note/need-help/conflict 四原语、协作预算与补步护栏，不引入第二份状态事实源。
 */
public class TaskBlackboard {

    private static final Logger log = LoggerFactory.getLogger(TaskBlackboard.class);
    public static final int DIGEST_THRESHOLD_CHARS = 400;
    public static final int DIGEST_MAX_CHARS = 200;
    public static final int FALLBACK_CHARS = 500;
    public static final int MERGE_THRESHOLD_CHARS = 1_200;
    public static final int NOTES_MAX_CHARS = 800;
    public static final int MAX_ENTRIES = TaskPlan.MAX_BLACKBOARD_ENTRIES;
    public static final int MAX_DISCOVERY_STEPS = 2;
    public static final int MAX_TOTAL_STEPS = 10;
    private static final int MAX_SIGNAL_CHARS = 600;
    private static final int MAX_CONTEXT_ENTRIES = 12;
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*\\}");

    private static final String DIGEST_SYSTEM = """
            你是任务结果摘要 Agent。把执行结果压缩成不超过 200 字的关键事实摘要，
            保留数字、时间、路径、结论；禁止编造与评价。""";
    private static final String NOTES_SYSTEM = """
            你是任务黑板笔记 Agent。把旧笔记与新的步骤摘要合并成不超过 800 字的滚动笔记，
            保留仍会影响后续步骤的目标、决定、关键数字与路径；禁止编造与评价。""";

    private final ModelRegistry registry;
    private final ModelGateway modelGateway;
    private final ContextBudget budget;
    private final ObjectMapper mapper;

    public TaskBlackboard(ModelRegistry registry, ModelGateway modelGateway, ContextBudget budget) {
        this(registry, modelGateway, budget, new ObjectMapper());
    }

    public TaskBlackboard(ModelRegistry registry, ModelGateway modelGateway, ContextBudget budget,
            ObjectMapper mapper) {
        this.registry = registry;
        this.modelGateway = modelGateway;
        this.budget = budget;
        this.mapper = mapper;
    }

    /** 单步上下文 = 人工/Agent note + 滚动笔记 + dependsOn 结果摘要，最后统一按 executor 预算裁剪。 */
    public List<String> contextFor(String goal, TaskPlan plan, TaskStep step) {
        List<Integer> dependencies = effectiveDependencies(step);
        List<ConversationMessage> messages = new ArrayList<>();
        List<BlackboardEntry> visibleNotes = plan.getBlackboard().stream()
                .filter(entry -> BlackboardEntry.NOTE.equals(entry.type()))
                .filter(entry -> entry.author().startsWith("HUMAN") || entry.stepId() <= step.getId())
                .sorted(Comparator.comparing(BlackboardEntry::createdAt))
                .toList();
        int noteStart = Math.max(0, visibleNotes.size() - MAX_CONTEXT_ENTRIES);
        for (BlackboardEntry entry : visibleNotes.subList(noteStart, visibleNotes.size())) {
            String source = entry.author().startsWith("HUMAN") ? "人工备注" : "Agent 发现";
            messages.add(ConversationMessage.user(source + "：" + entry.content()));
        }
        if (plan.getGlobalNotes() != null && !plan.getGlobalNotes().isBlank()
                && dependencies.stream().anyMatch(dep -> dep <= plan.getNoteThroughStepId())) {
            messages.add(ConversationMessage.user("任务滚动笔记：\n" + plan.getGlobalNotes()));
        }
        for (Integer dep : dependencies) {
            TaskStep dependency = findStep(plan, dep);
            String digest = dependency.getResultDigest();
            if (digest == null || digest.isBlank()) {
                String result = dependency.getResult();
                digest = result == null ? "" : truncate(result, FALLBACK_CHARS);
            }
            messages.add(ConversationMessage.user("步骤 " + dep + " 摘要：" + digest));
        }
        List<ConversationMessage> fitted = ContextTrimmer.trim(messages,
                budget.tokensFor(ContextBudget.CallSite.EXECUTOR), 1);
        return fitted.stream().map(ConversationMessage::text).toList();
    }

    /** 解析 Worker 的受限结构化输出；旧运行时/旧模型返回纯文本时自动视为 result。 */
    public ProcessedOutcome recordStepOutput(TaskPlan plan, TaskStep step, String rawOutput) {
        AgentOutput output = parseOutput(rawOutput);
        List<BlackboardEntry> added = new ArrayList<>();
        String result = clean(output.result(), null, 16_000);
        String needHelp = clean(output.needHelp(), null, MAX_SIGNAL_CHARS);
        if (result.isBlank() && needHelp.isBlank()) {
            result = clean(rawOutput, null, 16_000);
            if (result.isBlank()) {
                throw new IllegalStateException("Agent 未返回步骤结果或 need-help 信号。");
            }
        }

        if (!result.isBlank()) {
            BlackboardEntry resultEntry = BlackboardEntry.agent(BlackboardEntry.RESULT, step, result,
                    BlackboardEntry.RECORDED);
            append(plan, resultEntry);
            added.add(resultEntry);
        }
        for (String rawNote : output.notes()) {
            String note = clean(rawNote, null, MAX_SIGNAL_CHARS);
            if (!note.isBlank()) {
                BlackboardEntry entry = BlackboardEntry.agent(BlackboardEntry.NOTE, step, note,
                        BlackboardEntry.RECORDED);
                append(plan, entry);
                added.add(entry);
            }
        }

        BlackboardEntry helpEntry = null;
        if (!needHelp.isBlank()) {
            step.setResult(null);
            step.setResultDigest(null);
            helpEntry = BlackboardEntry.agent(BlackboardEntry.NEED_HELP, step, needHelp, BlackboardEntry.OPEN);
            append(plan, helpEntry);
            added.add(helpEntry);
        } else {
            recordStepResult(plan, step, result);
        }
        return new ProcessedOutcome(List.copyOf(added), output.discoveries(), helpEntry);
    }

    /** 步骤完成后回填 result 并生成 digest（同步，避免异步写回竞态）。 */
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
            String digest = summarize(DIGEST_SYSTEM, "步骤结果：\n" + input);
            step.setResultDigest(digest == null || digest.isBlank()
                    ? truncate(result, DIGEST_MAX_CHARS) : truncate(digest, DIGEST_MAX_CHARS));
        } catch (Exception e) {
            log.warn("步骤 {} digest 生成失败，使用截断兜底", step.getId());
            step.setResultDigest(truncate(result, DIGEST_MAX_CHARS));
        }
    }

    /** 同一执行波中两个未完成步骤声明写入同一资源时，先写 conflict，禁止并发执行。 */
    public List<BlackboardEntry> detectWriteConflicts(TaskPlan plan, List<Integer> stepIndexes) {
        Map<String, List<TaskStep>> byResource = new LinkedHashMap<>();
        for (int index : stepIndexes) {
            TaskStep step = plan.getSteps().get(index);
            for (String raw : step.getWriteResources()) {
                String resource = normalizeResource(raw);
                if (!resource.isBlank()) {
                    byResource.computeIfAbsent(resource, ignored -> new ArrayList<>()).add(step);
                }
            }
        }
        List<BlackboardEntry> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<TaskStep>> candidate : byResource.entrySet()) {
            if (candidate.getValue().size() < 2) {
                continue;
            }
            List<Integer> ids = candidate.getValue().stream().map(TaskStep::getId).sorted().toList();
            BlackboardEntry existing = plan.getBlackboard().stream()
                    .filter(entry -> BlackboardEntry.CONFLICT.equals(entry.type()))
                    .filter(entry -> Objects.equals(candidate.getKey(), entry.resource()))
                    .filter(entry -> entry.relatedStepIds().equals(ids))
                    .reduce((first, second) -> second).orElse(null);
            if (existing != null && (BlackboardEntry.HUMAN_RESOLVED.equals(existing.status())
                    || BlackboardEntry.PLANNER_RESOLVED.equals(existing.status()))) {
                continue;
            }
            if (existing != null && BlackboardEntry.OPEN.equals(existing.status())) {
                conflicts.add(existing);
                continue;
            }
            String content = "步骤 " + ids + " 同波写入共享资源 " + candidate.getKey();
            BlackboardEntry conflict = new BlackboardEntry(null, BlackboardEntry.CONFLICT,
                    ids.getFirst(), "planner", "SYSTEM", content, candidate.getKey(), ids,
                    BlackboardEntry.OPEN, null);
            append(plan, conflict);
            conflicts.add(conflict);
        }
        return List.copyOf(conflicts);
    }

    /** 应用 Planner 的受限 patch；已完成步骤不可改，副作用审批不可降级，补步全局最多 2 个。 */
    public ApplyResult applyDecision(TaskPlan plan, CollaborationDecision decision,
            List<BlackboardEntry> signals, AgentRegistry agentRegistry) {
        if (decision == null || !decision.resolved()) {
            return new ApplyResult(false, List.of());
        }
        List<TaskStep> originalSteps = copySteps(plan.getSteps());
        List<BlackboardEntry> originalEntries = List.copyOf(plan.getBlackboard());
        int originalDiscoveryStepCount = plan.getDiscoveryStepCount();
        boolean changed = false;
        List<Integer> patchedStepIds = new ArrayList<>();
        List<BlackboardEntry> changedEntries = new ArrayList<>();
        for (StepPatch patch : decision.patches()) {
            TaskStep step = findStepOrNull(plan, patch.stepId());
            if (step == null || hasResult(step)) {
                continue;
            }
            String before = stepFingerprint(step);
            if (patch.description() != null && !patch.description().isBlank()) {
                step.setDescription(truncate(patch.description().trim(), 1_000));
            }
            if (patch.agent() != null) {
                step.setAgent(agentRegistry.normalizeId(patch.agent()));
                step.setSkillId(null);
                step.setSkillVersion(null);
            }
            if (patch.needsApproval() != null) {
                step.setNeedsApproval(step.isNeedsApproval() || patch.needsApproval());
            }
            step.setNeedsApproval(step.isNeedsApproval() || requiresApproval(step.getDescription()));
            if (patch.dependsOn() != null) {
                step.setDependsOn(normalizeDependencies(patch.dependsOn(), step.getId()));
            }
            if (patch.writeResources() != null) {
                step.setWriteResources(normalizeResources(patch.writeResources()));
            }
            if (!before.equals(stepFingerprint(step))) {
                changed = true;
                patchedStepIds.add(step.getId());
            }
        }
        int sourceStepId = signals.stream().mapToInt(BlackboardEntry::stepId).min().orElse(1);
        DiscoveryResult discovery = appendDiscoveries(plan, decision.discoveries(), sourceStepId, agentRegistry);
        changed |= discovery.addedCount() > 0;
        changedEntries.addAll(discovery.entries());
        boolean effective = changed && signals.stream().allMatch(signal -> {
            if (BlackboardEntry.CONFLICT.equals(signal.type())) {
                return !conflictStillParallel(plan, signal);
            }
            return !BlackboardEntry.NEED_HELP.equals(signal.type())
                    || patchedStepIds.contains(signal.stepId()) || discovery.addedCount() > 0;
        });
        if (effective) {
            for (BlackboardEntry signal : signals) {
                BlackboardEntry resolved = replaceStatus(plan, signal.id(), BlackboardEntry.PLANNER_RESOLVED);
                if (resolved != null) {
                    changedEntries.add(resolved);
                }
            }
            String summary = clean(decision.summary(), "Planner 已完成协作调整", MAX_SIGNAL_CHARS);
            BlackboardEntry note = new BlackboardEntry(null, BlackboardEntry.NOTE, sourceStepId,
                    "planner", "PLANNER", summary, null, List.of(sourceStepId),
                    BlackboardEntry.RECORDED, null);
            append(plan, note);
            changedEntries.add(note);
        } else {
            // Planner 的裁决必须整体通过机械验证；部分 patch 不得泄漏到人工兜底后的执行计划。
            plan.setSteps(originalSteps);
            plan.setBlackboard(originalEntries);
            plan.setDiscoveryStepCount(originalDiscoveryStepCount);
            changedEntries.clear();
        }
        return new ApplyResult(effective, List.copyOf(changedEntries));
    }

    /** Agent discovery 直接形成受控补步；审批语义机械加固，超过 2 个只记预算耗尽 note。 */
    public DiscoveryResult appendDiscoveries(TaskPlan plan, List<TaskStep> requests,
            int sourceStepId, AgentRegistry agentRegistry) {
        if (requests == null || requests.isEmpty()) {
            return new DiscoveryResult(0, List.of());
        }
        List<TaskStep> mutable = new ArrayList<>(plan.getSteps());
        List<BlackboardEntry> entries = new ArrayList<>();
        int added = 0;
        for (TaskStep request : requests) {
            if (request == null || request.getDescription() == null || request.getDescription().isBlank()) {
                continue;
            }
            if (plan.getDiscoveryStepCount() >= MAX_DISCOVERY_STEPS || mutable.size() >= MAX_TOTAL_STEPS) {
                BlackboardEntry exhausted = new BlackboardEntry(null, BlackboardEntry.NOTE, sourceStepId,
                        "planner", "SYSTEM", "discovery 补步预算已用尽，本次请求未追加。", "discovery",
                        List.of(sourceStepId), BlackboardEntry.BUDGET_EXHAUSTED, null);
                append(plan, exhausted);
                entries.add(exhausted);
                break;
            }
            int id = mutable.size() + 1;
            TaskStep step = new TaskStep(id, truncate(request.getDescription().trim(), 1_000),
                    request.isNeedsApproval() || requiresApproval(request.getDescription()));
            step.setAgent(agentRegistry.normalizeId(request.getAgent()));
            List<Integer> rawDependencies = request.getDependsOn();
            step.setDependsOn(rawDependencies == null
                    ? (id <= 1 ? List.of() : List.of(Math.min(Math.max(sourceStepId, 1), id - 1)))
                    : normalizeDependencies(rawDependencies, id));
            step.setWriteResources(normalizeResources(request.getWriteResources()));
            mutable.add(step);
            plan.setDiscoveryStepCount(plan.getDiscoveryStepCount() + 1);
            added++;
            BlackboardEntry note = new BlackboardEntry(null, BlackboardEntry.NOTE, sourceStepId,
                    step.getAgent(), "AGENT", "discovery 补步 " + id + "：" + step.getDescription(),
                    "discovery", List.of(sourceStepId, id), BlackboardEntry.RECORDED, null);
            append(plan, note);
            entries.add(note);
        }
        plan.setSteps(List.copyOf(mutable));
        return new DiscoveryResult(added, List.copyOf(entries));
    }

    /** Planner 重规划预算耗尽后的 need-help 不再触发循环，把未解决事实交给 Judge 收口。 */
    public List<BlackboardEntry> exhaustNeedHelp(TaskPlan plan, List<BlackboardEntry> signals) {
        List<BlackboardEntry> updated = new ArrayList<>();
        for (BlackboardEntry signal : signals) {
            if (!BlackboardEntry.NEED_HELP.equals(signal.type())) {
                continue;
            }
            BlackboardEntry exhausted = replaceStatus(plan, signal.id(), BlackboardEntry.BUDGET_EXHAUSTED);
            if (exhausted != null) {
                updated.add(exhausted);
            }
            TaskStep step = findStepOrNull(plan, signal.stepId());
            if (step != null && !hasResult(step)) {
                recordStepResult(plan, step, "未解决求助：" + signal.content());
            }
        }
        return List.copyOf(updated);
    }

    public List<BlackboardEntry> openArbitrations(TaskPlan plan) {
        return plan.getBlackboard().stream()
                .filter(entry -> BlackboardEntry.OPEN.equals(entry.status()))
                .filter(entry -> BlackboardEntry.CONFLICT.equals(entry.type())
                        || BlackboardEntry.NEED_HELP.equals(entry.type()))
                .toList();
    }

    /** 人工仲裁必须在当前 OPEN 条目之后写 note，避免无说明地点击继续。 */
    public boolean hasHumanNoteForOpenArbitration(TaskPlan plan) {
        String openedAt = openArbitrations(plan).stream().map(BlackboardEntry::createdAt)
                .min(Comparator.naturalOrder()).orElse(null);
        if (openedAt == null) {
            return true;
        }
        return plan.getBlackboard().stream()
                .anyMatch(entry -> BlackboardEntry.NOTE.equals(entry.type())
                        && entry.author().startsWith("HUMAN")
                        && entry.createdAt().compareTo(openedAt) >= 0);
    }

    public List<BlackboardEntry> resolveOpenArbitrationsByHuman(TaskPlan plan) {
        List<BlackboardEntry> resolved = new ArrayList<>();
        for (BlackboardEntry entry : openArbitrations(plan)) {
            if (BlackboardEntry.CONFLICT.equals(entry.type())) {
                List<Integer> ids = entry.relatedStepIds().stream().sorted().toList();
                for (int i = 1; i < ids.size(); i++) {
                    TaskStep step = findStepOrNull(plan, ids.get(i));
                    if (step == null || hasResult(step)) {
                        continue;
                    }
                    List<Integer> dependencies = new ArrayList<>(
                            step.getDependsOn() == null ? List.of() : step.getDependsOn());
                    if (!dependencies.contains(ids.get(i - 1))) {
                        dependencies.add(ids.get(i - 1));
                    }
                    step.setDependsOn(normalizeDependencies(dependencies, step.getId()));
                }
            }
            BlackboardEntry updated = replaceStatus(plan, entry.id(), BlackboardEntry.HUMAN_RESOLVED);
            if (updated != null) {
                resolved.add(updated);
            }
        }
        return List.copyOf(resolved);
    }

    /** 每波完成后合并滚动笔记；失败水位不动。 */
    public void mergeWaveNotes(TaskPlan plan) {
        int newThrough = plan.getSteps().stream().filter(TaskBlackboard::hasResult)
                .mapToInt(TaskStep::getId).max().orElse(0);
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
        if (tokens * 2 < MERGE_THRESHOLD_CHARS) {
            plan.setGlobalNotes(joinNotes(plan.getGlobalNotes(), newDigests.toString()));
            plan.setNoteThroughStepId(newThrough);
            return;
        }
        try {
            String oldNotes = plan.getGlobalNotes() == null ? "" : plan.getGlobalNotes();
            String merged = summarize(NOTES_SYSTEM,
                    "已有笔记：\n" + oldNotes + "\n\n新增步骤摘要：\n" + newDigests);
            if (merged != null && !merged.isBlank()) {
                plan.setGlobalNotes(truncate(merged, NOTES_MAX_CHARS));
                plan.setNoteThroughStepId(newThrough);
            }
        } catch (Exception e) {
            log.warn("任务滚动笔记合并失败，水位不动（下次波次自然重试）");
        }
    }

    private String summarize(String systemPrompt, String userPrompt) {
        return modelGateway.call(new ModelInvocation(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER),
                systemPrompt, List.of(), userPrompt, ReasoningMode.DISABLED)).content();
    }

    public static boolean hasResult(TaskStep step) {
        return step.getResult() != null && !step.getResult().isBlank();
    }

    private AgentOutput parseOutput(String rawOutput) {
        String raw = rawOutput == null ? "" : rawOutput.trim();
        Matcher matcher = JSON_BLOCK.matcher(raw);
        if (matcher.find()) {
            try {
                AgentOutput parsed = mapper.readValue(matcher.group(), AgentOutput.class);
                if (parsed.hasSignal()) {
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("Agent 黑板结构化输出解析失败，按纯文本 result 降级：{}", e.getMessage());
            }
        }
        return new AgentOutput(raw, List.of(), null, List.of());
    }

    private void append(TaskPlan plan, BlackboardEntry entry) {
        plan.addBlackboardEntry(entry);
    }

    private static BlackboardEntry replaceStatus(TaskPlan plan, String id, String status) {
        List<BlackboardEntry> entries = plan.getBlackboard();
        for (int i = 0; i < entries.size(); i++) {
            if (Objects.equals(entries.get(i).id(), id)) {
                BlackboardEntry updated = entries.get(i).withStatus(status);
                entries.set(i, updated);
                return updated;
            }
        }
        return null;
    }

    private static List<Integer> effectiveDependencies(TaskStep step) {
        if (step.getDependsOn() == null) {
            return step.getId() <= 1 ? List.of() : List.of(step.getId() - 1);
        }
        return List.copyOf(step.getDependsOn());
    }

    private static TaskStep findStep(TaskPlan plan, int stepId) {
        TaskStep step = findStepOrNull(plan, stepId);
        if (step == null) {
            throw new IllegalStateException("操作失败：计划中找不到步骤 id=" + stepId);
        }
        return step;
    }

    private static TaskStep findStepOrNull(TaskPlan plan, int stepId) {
        return plan.getSteps().stream().filter(step -> step.getId() == stepId).findFirst().orElse(null);
    }

    private static List<Integer> normalizeDependencies(List<Integer> raw, int stepId) {
        if (raw == null) {
            return stepId <= 1 ? List.of() : List.of(stepId - 1);
        }
        List<Integer> valid = new ArrayList<>();
        for (Integer dep : raw) {
            if (dep != null && dep >= 1 && dep < stepId && !valid.contains(dep)) {
                valid.add(dep);
            }
        }
        return valid.isEmpty() && stepId > 1 ? List.of(stepId - 1) : List.copyOf(valid);
    }

    private static List<String> normalizeResources(List<String> resources) {
        if (resources == null) {
            return List.of();
        }
        return resources.stream().map(TaskBlackboard::normalizeResource).filter(value -> !value.isBlank())
                .distinct().limit(8).toList();
    }

    private static String normalizeResource(String raw) {
        if (raw == null) {
            return "";
        }
        return truncate(raw.trim().replace('\\', '/').toLowerCase(Locale.ROOT), 500);
    }

    private static boolean requiresApproval(String description) {
        String text = description == null ? "" : description.toLowerCase(Locale.ROOT);
        return text.contains("mail_send") || text.contains("发送邮件") || text.contains("delete")
                || text.contains("删除") || text.contains("覆盖") || text.contains("write_file")
                || text.contains("calendar_create") || text.contains("calendar_import");
    }

    private static boolean conflictStillParallel(TaskPlan plan, BlackboardEntry conflict) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < plan.getSteps().size(); i++) {
            TaskStep step = plan.getSteps().get(i);
            if (conflict.relatedStepIds().contains(step.getId())
                    && step.getWriteResources().stream()
                            .map(TaskBlackboard::normalizeResource)
                            .anyMatch(conflict.resource()::equals)) {
                indexes.add(i);
            }
        }
        if (indexes.size() < 2) {
            return false;
        }
        return WaveScheduler.waves(plan).stream()
                .anyMatch(wave -> wave.stream().filter(indexes::contains).count() > 1);
    }

    private static String stepFingerprint(TaskStep step) {
        return step.getDescription() + "|" + step.getAgent() + "|" + step.isNeedsApproval() + "|"
                + step.getSkillId() + "@" + step.getSkillVersion() + "|"
                + step.getDependsOn() + "|" + step.getWriteResources();
    }

    private static List<TaskStep> copySteps(List<TaskStep> steps) {
        List<TaskStep> copies = new ArrayList<>(steps.size());
        for (TaskStep step : steps) {
            TaskStep copy = new TaskStep(step.getId(), step.getDescription(), step.isNeedsApproval());
            copy.setAgent(step.getAgent());
            copy.setSkillId(step.getSkillId());
            copy.setSkillVersion(step.getSkillVersion());
            copy.setApproved(step.isApproved());
            copy.setResult(step.getResult());
            copy.setResultDigest(step.getResultDigest());
            copy.setDependsOn(step.getDependsOn() == null ? null : List.copyOf(step.getDependsOn()));
            copy.setWriteResources(step.getWriteResources());
            copies.add(copy);
        }
        return List.copyOf(copies);
    }

    private static String joinNotes(String oldNotes, String digests) {
        String old = oldNotes == null || oldNotes.isBlank() ? "" : oldNotes + "\n";
        return truncate(old + digests, NOTES_MAX_CHARS);
    }

    private static String clean(String value, String fallback, int max) {
        String selected = value == null || value.isBlank() ? fallback : value;
        return selected == null ? "" : truncate(selected.trim(), max);
    }

    private static String truncate(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public record ProcessedOutcome(List<BlackboardEntry> entries, List<TaskStep> discoveries,
            BlackboardEntry needHelp) {
    }

    public record ApplyResult(boolean changed, List<BlackboardEntry> entries) {
    }

    public record DiscoveryResult(int addedCount, List<BlackboardEntry> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AgentOutput(String result, List<String> notes, String needHelp, List<TaskStep> discoveries) {
        private AgentOutput {
            notes = notes == null ? List.of() : List.copyOf(notes).stream().limit(3).toList();
            discoveries = discoveries == null ? List.of() : List.copyOf(discoveries).stream().limit(4).toList();
        }

        boolean hasSignal() {
            return result != null || !notes.isEmpty() || needHelp != null || !discoveries.isEmpty();
        }
    }
}
