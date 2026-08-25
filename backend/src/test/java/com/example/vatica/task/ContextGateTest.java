package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.vatica.task.TaskPlan.TaskStep;

/** 迭代 29C：副作用上下文门禁的确定性规则。 */
class ContextGateTest {

    @Test
    void readOnlyStepContinuesWhenFactsAreStale() {
        TaskStep step = new TaskStep(1, "读取资料并分析", false);

        assertThat(ContextGate.evaluate(plan(step), step, true).allowed()).isTrue();
    }

    @Test
    void sideEffectNeedsApprovalEvenWhenPlannerForgotToFlagIt() {
        TaskStep step = new TaskStep(1, "调用 write_file 写入报告", false);

        ContextGate.Decision decision = ContextGate.evaluate(plan(step), step, false);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("人工批准");
    }

    @Test
    void deterministicDependencyDigestRequiresContextConfirmation() {
        TaskStep dependency = new TaskStep(1, "读取数据", false);
        dependency.setResult("原始结果");
        dependency.setResultDigest("头部…尾部");
        dependency.setResultDigestSource(TaskBlackboard.DIGEST_SOURCE_DETERMINISTIC_FALLBACK);
        TaskStep writer = new TaskStep(2, "写入文件", true);
        writer.setApproved(true);
        writer.setDependsOn(List.of(1));
        TaskPlan plan = plan(dependency, writer);

        ContextGate.Decision blocked = ContextGate.evaluate(plan, writer, false);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains("降级摘要");

        writer.setContextGateApproved(true);
        assertThat(ContextGate.evaluate(plan, writer, false).allowed()).isTrue();
    }

    @Test
    void staleFactsRequireExplicitConfirmationBeforeWrite() {
        TaskStep writer = new TaskStep(1, "写入文件", true);
        writer.setApproved(true);

        ContextGate.Decision blocked = ContextGate.evaluate(plan(writer), writer, true);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reason()).contains("待刷新");
    }

    private static TaskPlan plan(TaskStep... steps) {
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(steps));
        return plan;
    }
}
