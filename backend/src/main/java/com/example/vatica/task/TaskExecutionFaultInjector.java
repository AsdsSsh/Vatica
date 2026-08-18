package com.example.vatica.task;

import org.springframework.stereotype.Component;

/**
 * 迭代 18B：任务步骤故障注入边界。
 *
 * <p>生产实现默认不做任何动作；测试可以替换该 Bean，在步骤执行前注入超时、上游异常或权限异常，
 * 从而复用真实状态机、持久化和 SSE 链路，而不是只测孤立的异常分支。</p>
 */
@Component
public class TaskExecutionFaultInjector {

    /** 步骤真正调用运行时前的注入点。默认实现保持零开销。 */
    public void beforeStep(TaskRecord record, TaskPlan.TaskStep step) {
        // no-op
    }
}
