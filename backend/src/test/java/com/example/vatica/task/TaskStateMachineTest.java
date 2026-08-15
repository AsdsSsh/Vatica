package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

/**
 * 任务状态机单测（迭代 5 I5-7；迭代 5.5 增补 DONE→RETRY）：合法流转全部命中 +
 * 非法流转全部拒绝 + 终态闭包（FAILED 完全闭合，DONE 仅允许人工返工重开）。
 * 用穷举法（8×8 全组合断言），避免"只测写过的用例"漏掉意外放行。
 */
class TaskStateMachineTest {

    /** 合法流转全命中（含 5.5 的 REVIEW→RETRY/NEEDS_REVISION、RETRY/NEEDS_REVISION→RUNNING、DONE→RETRY）。 */
    @Test
    void legalTransitions() {
        assertLegal(TaskStatus.PENDING, TaskStatus.RUNNING);
        assertLegal(TaskStatus.PENDING, TaskStatus.FAILED);

        assertLegal(TaskStatus.RUNNING, TaskStatus.PENDING_APPROVAL);
        assertLegal(TaskStatus.RUNNING, TaskStatus.REVIEW);
        assertLegal(TaskStatus.RUNNING, TaskStatus.FAILED);

        assertLegal(TaskStatus.PENDING_APPROVAL, TaskStatus.RUNNING);
        assertLegal(TaskStatus.PENDING_APPROVAL, TaskStatus.FAILED);

        assertLegal(TaskStatus.REVIEW, TaskStatus.DONE);
        assertLegal(TaskStatus.REVIEW, TaskStatus.RETRY);
        assertLegal(TaskStatus.REVIEW, TaskStatus.NEEDS_REVISION);
        assertLegal(TaskStatus.REVIEW, TaskStatus.FAILED);

        assertLegal(TaskStatus.RETRY, TaskStatus.RUNNING);
        assertLegal(TaskStatus.RETRY, TaskStatus.NEEDS_REVISION);
        assertLegal(TaskStatus.RETRY, TaskStatus.FAILED);

        assertLegal(TaskStatus.NEEDS_REVISION, TaskStatus.RUNNING);
        assertLegal(TaskStatus.NEEDS_REVISION, TaskStatus.FAILED);

        assertLegal(TaskStatus.DONE, TaskStatus.RETRY);

        assertLegal(TaskStatus.PENDING, TaskStatus.CANCELLED);
        assertLegal(TaskStatus.RUNNING, TaskStatus.CANCELLED);
        assertLegal(TaskStatus.PENDING_APPROVAL, TaskStatus.CANCELLED);
    }

    /** 穷举：除上述合法流转外，其余全部非法（包括从终态出发、回退 PENDING 等）。 */
    @Test
    void allOtherTransitionsAreIllegal() {
        EnumSet<TaskStatus> all = EnumSet.allOf(TaskStatus.class);
        for (TaskStatus from : all) {
            for (TaskStatus to : all) {
                boolean legal = isLegal(from, to);
                if (!legal) {
                    assertThatThrownBy(() -> TaskStateMachine.requireTransition(from, to))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("非法状态流转")
                            .hasMessageContaining(from.name());
                    assertThat(TaskStateMachine.canTransition(from, to)).isFalse();
                }
            }
        }
    }

    /** 终态闭包：FAILED/CANCELLED 无任何流出；DONE 仅允许人工返工重开（→RETRY，5.5 增补），其余全部拒绝。 */
    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (TaskStatus to : EnumSet.allOf(TaskStatus.class)) {
            assertThat(TaskStateMachine.canTransition(TaskStatus.FAILED, to)).isFalse();
            assertThat(TaskStateMachine.canTransition(TaskStatus.CANCELLED, to)).isFalse();
        }
        assertThat(TaskStateMachine.canTransition(TaskStatus.DONE, TaskStatus.RETRY)).isTrue();
        for (TaskStatus to : EnumSet.allOf(TaskStatus.class)) {
            if (to != TaskStatus.RETRY) {
                assertThat(TaskStateMachine.canTransition(TaskStatus.DONE, to)).isFalse();
            }
        }
        // 业务终态语义不变：DONE/FAILED/CANCELLED 不再接受审批/执行推进，DONE→RETRY 是唯一的人工返工例外
        assertThat(TaskStatus.DONE.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
    }

    /** requireTransition 合法流转不抛异常。 */
    @Test
    void requireTransitionLegalIsNoop() {
        TaskStateMachine.requireTransition(TaskStatus.PENDING, TaskStatus.RUNNING);
        TaskStateMachine.requireTransition(TaskStatus.REVIEW, TaskStatus.DONE);
    }

    private static boolean isLegal(TaskStatus from, TaskStatus to) {
        return switch (from) {
            case PENDING -> to == TaskStatus.RUNNING || to == TaskStatus.FAILED || to == TaskStatus.CANCELLED;
            case RUNNING -> to == TaskStatus.PENDING_APPROVAL || to == TaskStatus.REVIEW || to == TaskStatus.FAILED
                    || to == TaskStatus.CANCELLED;
            case PENDING_APPROVAL -> to == TaskStatus.RUNNING || to == TaskStatus.FAILED || to == TaskStatus.CANCELLED;
            case REVIEW -> to == TaskStatus.DONE || to == TaskStatus.RETRY || to == TaskStatus.NEEDS_REVISION
                    || to == TaskStatus.FAILED;
            case RETRY -> to == TaskStatus.RUNNING || to == TaskStatus.NEEDS_REVISION || to == TaskStatus.FAILED;
            case NEEDS_REVISION -> to == TaskStatus.RUNNING || to == TaskStatus.FAILED;
            case DONE -> to == TaskStatus.RETRY;
            case FAILED, CANCELLED -> false;
        };
    }

    private static void assertLegal(TaskStatus from, TaskStatus to) {
        assertThat(TaskStateMachine.canTransition(from, to))
                .as("%s → %s 应为合法流转", from, to)
                .isTrue();
    }
}
