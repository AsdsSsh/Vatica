package com.example.vatica.task;

import com.example.vatica.config.EphemeralCredential;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.mail.MailConnectionSettings;

/** 创建任务请求（迭代 18C 增加固定评测用例标识）。 */
public record TaskCreateRequest(String goal, FilePermissionPolicy permission,
        EphemeralCredential credential, MailConnectionSettings mailCredential, String benchmarkCaseId) {

    public TaskCreateRequest(String goal, FilePermissionPolicy permission) {
        this(goal, permission, null, null, null);
    }

    public TaskCreateRequest(String goal, FilePermissionPolicy permission, EphemeralCredential credential) {
        this(goal, permission, credential, null, null);
    }

    public TaskCreateRequest(String goal, FilePermissionPolicy permission, EphemeralCredential credential,
            MailConnectionSettings mailCredential) {
        this(goal, permission, credential, mailCredential, null);
    }
}
