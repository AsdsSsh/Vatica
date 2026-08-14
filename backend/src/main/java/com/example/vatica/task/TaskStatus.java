package com.example.vatica.task;

/**
 * 任务状态（迭代 5 核心闭环 I5-2）。
 *
 * <p>状态机设计（面试可讲）：<b>HITL 是执行前人工审批（入口），评测是执行后质量门禁（出口）</b>，
 * 两者在状态机里各占一段：PENDING_APPROVAL 是审批点挂起，REVIEW/RETRY/NEEDS_REVISION 是评测段
 * （迭代 5.5 落地执行逻辑，本迭代定义状态与合法流转并用单测锁死）。
 */
public enum TaskStatus {

    /** 已创建、计划已生成，等待用户审批计划（入口 HITL）。 */
    PENDING,

    /** 计划已批准，执行中。 */
    RUNNING,

    /** 执行到需要人工审批的敏感步骤，挂起等待审批。 */
    PENDING_APPROVAL,

    /** 全部步骤执行完，进入质量门禁（迭代 5.5 由 Judge 评分决定去向；本迭代自动转 DONE）。 */
    REVIEW,

    /** 交付完成（终态）。 */
    DONE,

    /** 低分自动返工（限 2 次，迭代 5.5 落地）。 */
    RETRY,

    /** 返工超限/人工判定不合格，交人工处理（迭代 5.5 落地）。 */
    NEEDS_REVISION,

    /** 执行异常/被拒绝（终态）。 */
    FAILED;

    /** 是否终态。 */
    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}
