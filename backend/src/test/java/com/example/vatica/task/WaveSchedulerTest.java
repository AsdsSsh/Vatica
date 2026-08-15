package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 并行波次调度单测（迭代 6 I6-1）：默认顺序（向后兼容）、显式并行、依赖链分层、
 * 审批点屏障、已批准步骤重归并行波、非法依赖兜底。
 */
class WaveSchedulerTest {

    /** 老计划（dependsOn 全为 null）→ 每个步骤独占一波（顺序执行，与迭代 5 行为一致）。 */
    @Test
    void nullDependenciesFallBackSequential() {
        TaskPlan plan = planOf(
                step(1, false, null),
                step(2, false, null),
                step(3, false, null));

        assertThat(WaveScheduler.waves(plan))
                .containsExactly(List.of(0), List.of(1), List.of(2));
    }

    /** 显式 [] 声明并行的步骤归入同一波。 */
    @Test
    void independentStepsShareWave() {
        TaskPlan plan = planOf(
                step(1, false, List.of()),
                step(2, false, List.of()),
                step(3, false, List.of()));

        assertThat(WaveScheduler.waves(plan)).containsExactly(List.of(0, 1, 2));
    }

    /** 依赖链：2 依赖 1、3 依赖 2 → 三层波。 */
    @Test
    void dependencyChainSplitsWaves() {
        TaskPlan plan = planOf(
                step(1, false, List.of()),
                step(2, false, List.of(1)),
                step(3, false, List.of(2)));

        assertThat(WaveScheduler.waves(plan))
                .containsExactly(List.of(0), List.of(1), List.of(2));
    }

    /** 菱形依赖：3、4 都依赖 1、2 → 1、2 并行一波，3、4 并行第二波。 */
    @Test
    void diamondDependencyTwoWaves() {
        TaskPlan plan = planOf(
                step(1, false, List.of()),
                step(2, false, List.of()),
                step(3, false, List.of(1, 2)),
                step(4, false, List.of(1, 2)));

        assertThat(WaveScheduler.waves(plan))
                .containsExactly(List.of(0, 1), List.of(2, 3));
    }

    /** 审批点屏障：未批准的审批步骤独占一波，同层并行组被拆开（不允许并行绕过人工确认）。 */
    @Test
    void unapprovedStepBreaksParallelWave() {
        TaskPlan plan = planOf(
                step(1, false, List.of()),
                step(2, true, List.of()),
                step(3, false, List.of()));

        assertThat(WaveScheduler.waves(plan))
                .containsExactly(List.of(0), List.of(1), List.of(2));
    }

    /** 审批通过后步骤重归并行波（续批后 2、3 可并行执行）。 */
    @Test
    void approvedStepRejoinsParallelWave() {
        TaskStep step2 = step(2, true, List.of());
        step2.setApproved(true);
        TaskPlan plan = planOf(
                step(1, false, List.of()),
                step2,
                step(3, false, List.of()));

        assertThat(WaveScheduler.waves(plan)).containsExactly(List.of(0, 1, 2));
    }

    /** 非法依赖引用（越界/自引用）忽略，合法引用保留。 */
    @Test
    void invalidDependencyRefsIgnored() {
        TaskPlan plan = planOf(
                step(1, false, List.of()),
                step(2, false, List.of(1)),
                step(3, false, List.of(99, 1, 3, -1)));

        assertThat(WaveScheduler.waves(plan))
                .containsExactly(List.of(0), List.of(1, 2));
    }

    private static TaskPlan planOf(TaskStep... steps) {
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(steps));
        return plan;
    }

    private static TaskStep step(int id, boolean needsApproval, List<Integer> dependsOn) {
        TaskStep step = new TaskStep(id, "步骤" + id, needsApproval);
        step.setDependsOn(dependsOn);
        return step;
    }
}
