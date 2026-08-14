package com.example.vatica.task;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态机（迭代 5 I5-2）：集中定义合法流转，执行层改状态必须经此校验，
 * 非法流转直接抛异常——状态机是"审批点/返工限次"这些业务规则的唯一权威
 * （防执行层各处手写 if 判断漂移）。
 *
 * <p>合法流转图：
 * <pre>
 * PENDING ──审批计划──▶ RUNNING ──敏感步骤──▶ PENDING_APPROVAL ──审批步骤──▶ RUNNING
 *    │                    │  └──全部完成──▶ REVIEW ──▶ DONE（5.5 接入 Judge 后分流 RETRY/NEEDS_REVISION）
 *    └──执行失败──▶ FAILED ◀──执行异常──────┘                └──▶ RETRY ──▶ RUNNING（5.5 自动返工）
 *                                                             └──▶ NEEDS_REVISION ──▶ RUNNING（5.5 人工返工）
 * </pre>
 * REVIEW→RETRY/NEEDS_REVISION 与 RETRY/NEEDS_REVISION→RUNNING 为迭代 5.5 预留：
 * 状态与流转在 5 定义并单测锁死，执行逻辑 5.5 落地。
 */
public final class TaskStateMachine {

    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        allow(TaskStatus.PENDING, TaskStatus.RUNNING);          // 计划审批通过
        allow(TaskStatus.PENDING, TaskStatus.FAILED);           // 规划/启动失败

        allow(TaskStatus.RUNNING, TaskStatus.PENDING_APPROVAL); // 命中敏感步骤审批点
        allow(TaskStatus.RUNNING, TaskStatus.REVIEW);           // 全部步骤执行完
        allow(TaskStatus.RUNNING, TaskStatus.FAILED);           // 执行异常

        allow(TaskStatus.PENDING_APPROVAL, TaskStatus.RUNNING); // 步骤审批通过，继续执行
        allow(TaskStatus.PENDING_APPROVAL, TaskStatus.FAILED);  // 审批期间异常

        allow(TaskStatus.REVIEW, TaskStatus.DONE);              // 本迭代：评测段占位，自动交付
        allow(TaskStatus.REVIEW, TaskStatus.RETRY);             // 5.5：Judge 低分自动返工
        allow(TaskStatus.REVIEW, TaskStatus.NEEDS_REVISION);    // 5.5：返工超限交人工
        allow(TaskStatus.REVIEW, TaskStatus.FAILED);            // 评测异常

        allow(TaskStatus.RETRY, TaskStatus.RUNNING);            // 5.5：重新执行
        allow(TaskStatus.RETRY, TaskStatus.NEEDS_REVISION);     // 5.5：返工超限交人工
        allow(TaskStatus.RETRY, TaskStatus.FAILED);

        allow(TaskStatus.NEEDS_REVISION, TaskStatus.RUNNING);   // 5.5：人工返工重跑
        allow(TaskStatus.NEEDS_REVISION, TaskStatus.FAILED);
    }

    private TaskStateMachine() {
    }

    private static void allow(TaskStatus from, TaskStatus to) {
        TRANSITIONS.computeIfAbsent(from, k -> EnumSet.noneOf(TaskStatus.class)).add(to);
    }

    /** 是否合法流转。 */
    public static boolean canTransition(TaskStatus from, TaskStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 合法流转校验：非法直接抛异常（附全部合法目标）。 */
    public static void requireTransition(TaskStatus from, TaskStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("非法状态流转：" + from + " → " + to
                    + "。允许的目标状态：" + TRANSITIONS.getOrDefault(from, Set.of()));
        }
    }
}
