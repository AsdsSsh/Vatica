package com.example.vatica.task;

import com.example.vatica.permission.FilePermissionPolicy;

/** 创建任务请求（迭代 11 增加权限快照）。 */
public record TaskCreateRequest(String goal, FilePermissionPolicy permission) {
}
