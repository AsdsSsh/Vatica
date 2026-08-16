package com.example.vatica.task;

import com.example.vatica.config.EphemeralCredential;
import com.example.vatica.permission.FilePermissionPolicy;

/** 创建任务请求（迭代 11 增加权限快照；迭代 13 I13-5 增加临时凭据）。 */
public record TaskCreateRequest(String goal, FilePermissionPolicy permission,
        EphemeralCredential credential) {

    public TaskCreateRequest(String goal, FilePermissionPolicy permission) {
        this(goal, permission, null);
    }
}
