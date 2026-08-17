package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.vatica.config.ModelRegistry;
import com.example.vatica.context.ContextBudget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 迭代 15 I15-11：任务黑板——dependsOn 最小上下文、[] 为空、null 依赖上一步、
 * 长结果生成 digest、每波合并滚动笔记、返工重置。
 */
class TaskBlackboardTest {

    private ModelRegistry registry;
    private TaskBlackboard blackboard;

    @BeforeEach
    void setUp() {
        registry = mock(ModelRegistry.class);
        blackboard = new TaskBlackboard(registry, new ContextBudget(0, 0, 0, 0, 0));
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
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(registry.summarizerClient()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn("关键数字：123");

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
}
