package com.example.vatica.task;

/**
 * 任务不存在（迭代 9 I9-3）：与业务校验失败（{@link IllegalArgumentException} → 400）区分，
 * 资源语义交给 HTTP 状态码表达 → 404。
 *
 * <p>设计说明：继承 {@link IllegalArgumentException} 保持既有调用方兼容
 * （TaskService 内部与既有测试按父类断言），全局异常处理器为它声明
 * 更具体类型的分支，映射 404。
 */
public class TaskNotFoundException extends IllegalArgumentException {

    public TaskNotFoundException(String taskId) {
        super("操作失败：任务不存在（id=" + taskId + "）。");
    }
}
