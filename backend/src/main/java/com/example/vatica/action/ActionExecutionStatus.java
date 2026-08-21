package com.example.vatica.action;

/**
 * 迭代 25B：一项已批准副作用的持久化执行状态。
 *
 * <p>状态只描述业务动作，不记录模型推理或完整提示词。CANCELLED 仅允许由尚未开始的动作进入，
 * 已执行的外部副作用必须保留实际结果。</p>
 */
public enum ActionExecutionStatus {
    APPROVED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
