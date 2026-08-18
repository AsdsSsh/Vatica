package com.example.vatica.task;

/**
 * 任务详情（迭代 9 I9-3 契约显式化）：详情接口与 SSE 事件负载同构——
 * 事件负载 = 详情 + {@code type} 字段（TaskEventPublisher 快照），前端一个类型两处复用。
 *
 * @param plan        任务计划（解析后的 JSON 对象；数据损坏时为提示字符串）
 * @param currentStep 下一个待执行步骤下标（全部完成/未开始执行时为 -1）
 * @param pendingStepId 挂起审批的步骤 id（无挂起时为 -1）
 * @param score       Judge 评分（未评测为 null）
 * @param verdict     PASS / FAIL（未评测为 null）
 * @param error       失败/终止/评测不合格原因（正常为 null）
 * @param recoverable 迭代 13：服务重启中断后是否可"继续执行"
 * @param executionAttempt 迭代 18：执行尝试次数
 * @param executionRuntime 迭代 18：本次执行使用的运行时（legacy / agentscope）
 * @param lastHeartbeatAt 迭代 18：最近一次执行心跳
 * @param recoveryApprovalRequired 迭代 18：恢复是否需要人工确认中断步骤
 */
public record TaskDetailDto(String id, String goal, String status, String createdAt,
        int currentStep, int pendingStepId, Integer score, String verdict, int reworkCount,
        String error, Object plan, boolean recoverable, int executionAttempt, String executionRuntime,
        String lastHeartbeatAt, boolean recoveryApprovalRequired) {
}
