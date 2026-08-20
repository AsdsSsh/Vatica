package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelResponse;
import com.example.vatica.model.ModelUsage;
import com.example.vatica.runtime.AgentRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 迭代 15 I15-11：任务黑板——dependsOn 最小上下文、[] 为空、null 依赖上一步、
 * 长结果生成 digest、每波合并滚动笔记、返工重置。
 */
class TaskBlackboardTest {

    private ModelRegistry registry;
    private ModelGateway modelGateway;
    private TaskBlackboard blackboard;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        modelGateway = mock(ModelGateway.class);
        blackboard = new TaskBlackboard(registry, modelGateway, new ContextBudget(0, 0, 0, 0, 0));
    }

    private static TaskPlan planWithSteps(int count) {
        TaskPlan plan = new TaskPlan();
        List<TaskStep> steps = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            steps.add(new TaskStep(i, "步骤" + i, false));
        }
        plan.setSteps(steps);
        return plan;
    }

    @Test
    void dependsOnInjectsOnlyDeclaredDependencies() {
        TaskPlan plan = planWithSteps(4);
        TaskStep s4 = plan.getSteps().get(3);
        s4.setDependsOn(List.of(1, 3));
        for (int i = 0; i < 4; i++) {
            TaskStep step = plan.getSteps().get(i);
            step.setResult("步骤" + step.getId() + "结果");
            step.setResultDigest("摘要" + step.getId());
        }

        List<String> context = blackboard.contextFor("目标", plan, s4);

        assertThat(context).anyMatch(v -> v.contains("步骤 1 摘要：摘要1"));
        assertThat(context).anyMatch(v -> v.contains("步骤 3 摘要：摘要3"));
        assertThat(context).noneMatch(v -> v.contains("步骤 2"));
        assertThat(context).noneMatch(v -> v.contains("步骤 4 摘要"));
    }

    @Test
    void explicitEmptyDependenciesGivesEmptyContext() {
        TaskPlan plan = planWithSteps(2);
        TaskStep parallel = plan.getSteps().get(1);
        parallel.setDependsOn(List.of());
        plan.getSteps().get(0).setResult("已完成");

        assertThat(blackboard.contextFor("目标", plan, parallel)).isEmpty();
    }

    @Test
    void nullDependenciesFallBackToPreviousStep() {
        TaskPlan plan = planWithSteps(2);
        TaskStep second = plan.getSteps().get(1);
        second.setDependsOn(null);
        TaskStep first = plan.getSteps().get(0);
        first.setResult("第一步结果");
        first.setResultDigest("第一步摘要");

        assertThat(blackboard.contextFor("目标", plan, second)).containsExactly("步骤 1 摘要：第一步摘要");
    }

    @Test
    void longResultGetsSummarizedDigest() {
        String longResult = "数字结果".repeat(200);
        when(registry.activeSlotFor(ModelSlot.CAP_SUMMARIZER)).thenReturn(
                new ModelSlot("summary", "Summary", "openai", "https://example.test",
                        "k", "summary-model", 0.2, true));
        when(modelGateway.call(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelResponse("关键数字：123", "", ModelUsage.empty()));

        TaskPlan plan = planWithSteps(1);
        TaskStep step = plan.getSteps().get(0);
        blackboard.recordStepResult(plan, step, longResult);

        assertThat(step.getResult()).isEqualTo(longResult);
        assertThat(step.getResultDigest()).isEqualTo("关键数字：123");
    }

    @Test
    void shortResultIsItsOwnDigestWithoutCallingModel() {
        TaskPlan plan = planWithSteps(1);
        TaskStep step = plan.getSteps().get(0);
        blackboard.recordStepResult(plan, step, "短结果");

        assertThat(step.getResultDigest()).isEqualTo("短结果");
    }

    @Test
    void mergeWaveNotesAdvancesWatermarkWithoutModelWhenSmall() {
        TaskPlan plan = planWithSteps(3);
        for (TaskStep step : plan.getSteps()) {
            step.setResult("结果" + step.getId());
            step.setResultDigest("摘要" + step.getId());
        }

        blackboard.mergeWaveNotes(plan);

        assertThat(plan.getNoteThroughStepId()).isEqualTo(3);
        assertThat(plan.getGlobalNotes()).contains("摘要1").contains("摘要2").contains("摘要3");
    }

    /** 17B：结构化 Worker 输出形成 result/note/need-help，求助步骤保持未完成等待调度裁决。 */
    @Test
    void parsesRestrictedWorkerOutputIntoBlackboardPrimitives() {
        TaskPlan plan = planWithSteps(1);
        TaskStep step = plan.getSteps().getFirst();

        TaskBlackboard.ProcessedOutcome outcome = blackboard.recordStepOutput(plan, step, """
                {"result":"已找到 2 个候选文件","notes":["第二个文件更新时间更新"],
                 "needHelp":"需要 workspace Agent 确认目标路径",
                 "discoveries":[{"description":"核验目标路径","agent":"workspace","needsApproval":false}]}
                """);

        assertThat(step.getResult()).isNull();
        assertThat(outcome.needHelp()).isNotNull();
        assertThat(outcome.discoveries()).hasSize(1);
        assertThat(plan.getBlackboard()).extracting(BlackboardEntry::type)
                .containsExactly(BlackboardEntry.RESULT, BlackboardEntry.NOTE, BlackboardEntry.NEED_HELP);
        assertThat(plan.getBlackboard().getLast().status()).isEqualTo(BlackboardEntry.OPEN);
    }

    /** 17B：人工 note 不依赖步骤关系，下一波 Worker 立即读到。 */
    @Test
    void humanNoteIsVisibleToNextWave() {
        TaskPlan plan = planWithSteps(2);
        plan.addBlackboardEntry(new BlackboardEntry(null, BlackboardEntry.NOTE, 1, "human", "HUMAN:alice",
                "最终文件名使用 approved-report.docx", null, List.of(), BlackboardEntry.RECORDED, null));

        assertThat(blackboard.contextFor("目标", plan, plan.getSteps().get(1)))
                .anyMatch(value -> value.contains("人工备注") && value.contains("approved-report.docx"));
    }

    /** 17B：同波同资源写入先冲突；Planner 增加依赖后条目关闭且调度变为串行。 */
    @Test
    void detectsAndResolvesSharedWriteConflict() {
        TaskPlan plan = planWithSteps(2);
        TaskStep first = plan.getSteps().get(0);
        TaskStep second = plan.getSteps().get(1);
        first.setDependsOn(List.of());
        second.setDependsOn(List.of());
        first.setWriteResources(List.of("file:C:\\work\\report.docx"));
        second.setWriteResources(List.of("FILE:c:/work/report.docx"));

        List<BlackboardEntry> conflicts = blackboard.detectWriteConflicts(plan, List.of(0, 1));
        CollaborationDecision decision = new CollaborationDecision(true, "先生成再校验",
                List.of(new CollaborationDecision.StepPatch(2, null, null, null,
                        List.of(1), null)), List.of());

        TaskBlackboard.ApplyResult applied = blackboard.applyDecision(
                plan, decision, conflicts, new AgentRegistry());

        assertThat(applied.changed()).isTrue();
        assertThat(second.getDependsOn()).containsExactly(1);
        assertThat(WaveScheduler.waves(plan)).containsExactly(List.of(0), List.of(1));
        assertThat(plan.getBlackboard()).filteredOn(entry -> BlackboardEntry.CONFLICT.equals(entry.type()))
                .extracting(BlackboardEntry::status).containsExactly(BlackboardEntry.PLANNER_RESOLVED);
    }

    /** 17B：没有真正消除冲突的 Planner patch 整体回滚，人工兜底不会继承半成品计划。 */
    @Test
    void ineffectivePlannerDecisionIsRolledBack() {
        TaskPlan plan = planWithSteps(2);
        plan.getSteps().forEach(step -> {
            step.setDependsOn(List.of());
            step.setWriteResources(List.of("file:report.docx"));
        });
        List<BlackboardEntry> conflicts = blackboard.detectWriteConflicts(plan, List.of(0, 1));
        CollaborationDecision ineffective = new CollaborationDecision(true, "只改描述但未解决并发写",
                List.of(new CollaborationDecision.StepPatch(2, "改过但仍并发的步骤", null,
                        null, null, null)), List.of());

        TaskBlackboard.ApplyResult applied = blackboard.applyDecision(
                plan, ineffective, conflicts, new AgentRegistry());

        assertThat(applied.changed()).isFalse();
        assertThat(plan.getSteps().get(1).getDescription()).isEqualTo("步骤2");
        assertThat(plan.getBlackboard()).filteredOn(entry -> BlackboardEntry.CONFLICT.equals(entry.type()))
                .extracting(BlackboardEntry::status).containsExactly(BlackboardEntry.OPEN);
    }

    /** 17B：人工仲裁采用安全默认值，把共享写步骤按编号串行化。 */
    @Test
    void humanResolutionSerializesConflictingSteps() {
        TaskPlan plan = planWithSteps(2);
        plan.getSteps().forEach(step -> {
            step.setDependsOn(List.of());
            step.setWriteResources(List.of("file:report.docx"));
        });
        blackboard.detectWriteConflicts(plan, List.of(0, 1));

        List<BlackboardEntry> resolved = blackboard.resolveOpenArbitrationsByHuman(plan);

        assertThat(resolved).extracting(BlackboardEntry::status)
                .containsExactly(BlackboardEntry.HUMAN_RESOLVED);
        assertThat(plan.getSteps().get(1).getDependsOn()).containsExactly(1);
    }

    /** 17B：discovery 全任务最多补 2 步，动态副作用步骤必须审批。 */
    @Test
    void discoveryIsCappedAndSideEffectsRequireApproval() {
        TaskPlan plan = planWithSteps(1);
        TaskStep sendMail = new TaskStep(0, "调用 mail_send 发送结果", false);
        sendMail.setAgent("pim");
        TaskStep calculate = new TaskStep(0, "补算总计", false);
        calculate.setAgent("research");
        TaskStep ignored = new TaskStep(0, "无限探索", false);

        TaskBlackboard.DiscoveryResult result = blackboard.appendDiscoveries(plan,
                List.of(sendMail, calculate, ignored), 1, new AgentRegistry());

        assertThat(result.addedCount()).isEqualTo(2);
        assertThat(plan.getDiscoveryStepCount()).isEqualTo(2);
        assertThat(plan.getSteps()).hasSize(3);
        assertThat(plan.getSteps().get(1).isNeedsApproval()).isTrue();
        assertThat(result.entries()).anyMatch(entry -> BlackboardEntry.BUDGET_EXHAUSTED.equals(entry.status()));
    }

    /** 17B：黑板对象只挂在各自 TaskPlan，两个并发任务不会共享条目。 */
    @Test
    void taskPlansKeepBlackboardsIsolated() {
        TaskPlan first = planWithSteps(1);
        TaskPlan second = planWithSteps(1);

        blackboard.recordStepOutput(first, first.getSteps().getFirst(), "任务一结果");

        assertThat(first.getBlackboard()).hasSize(1);
        assertThat(second.getBlackboard()).isEmpty();
    }

    /** 17B：无 result/need-help 的结构化输出用原始内容兜底，不能带着未完成步骤进入 Judge。 */
    @Test
    void noteOnlyOutputStillCompletesTheStep() {
        TaskPlan plan = planWithSteps(1);
        TaskStep step = plan.getSteps().getFirst();

        blackboard.recordStepOutput(plan, step, "{\"notes\":[\"路径已核验\"]}");

        assertThat(TaskBlackboard.hasResult(step)).isTrue();
        assertThat(step.getResult()).contains("路径已核验");
        assertThat(plan.getBlackboard()).extracting(BlackboardEntry::type)
                .containsExactly(BlackboardEntry.RESULT, BlackboardEntry.NOTE);
    }

    /** 17B：限额位于 TaskPlan 唯一入口，HumanAgent 直写也不能绕过；OPEN 仲裁优先保留。 */
    @Test
    void blackboardLimitAlsoAppliesToHumanEntries() {
        TaskPlan plan = planWithSteps(1);
        BlackboardEntry open = new BlackboardEntry(null, BlackboardEntry.CONFLICT, 1, "planner", "SYSTEM",
                "等待人工仲裁", "file:report.docx", List.of(1), BlackboardEntry.OPEN, null);
        plan.addBlackboardEntry(open);
        for (int i = 0; i < TaskBlackboard.MAX_ENTRIES + 8; i++) {
            plan.addBlackboardEntry(new BlackboardEntry(null, BlackboardEntry.NOTE, 1, "human", "HUMAN:alice",
                    "备注 " + i, null, List.of(), BlackboardEntry.RECORDED, null));
        }

        assertThat(plan.getBlackboard()).hasSize(TaskBlackboard.MAX_ENTRIES);
        assertThat(plan.getBlackboard()).contains(open);
        assertThat(plan.getBlackboard().getLast().content()).isEqualTo("备注 71");
    }
}
