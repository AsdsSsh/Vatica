package com.example.vatica.task;

import com.example.vatica.config.EphemeralCredential;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.mail.MailConnectionSettings;

/** 创建任务请求（迭代 11 增加权限快照；迭代 13 I13-5 增加临时凭据）。 */
public record TaskCreateRequest(String goal, FilePermissionPolicy permission,
        EphemeralCredential credential, MailConnectionSettings mailCredential) {

    public TaskCreateRequest(String goal, FilePermissionPolicy permission) {
        this(goal, permission, null, null);
    }

    public TaskCreateRequest(String goal, FilePermissionPolicy permission, EphemeralCredential credential) {
        this(goal, permission, credential, null);
    }
}
