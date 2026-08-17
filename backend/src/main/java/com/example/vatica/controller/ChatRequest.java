package com.example.vatica.controller;

import com.example.vatica.config.EphemeralCredential;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.mail.MailConnectionSettings;

/**
 * 对话请求体（迭代 13 I13-5 增加临时凭据；迭代 15 I15-4 增加深思开关）。
 *
 * @param message    用户消息内容
 * @param sessionId  可选会话 ID；为空时使用默认会话（迭代 2.5 会话短期记忆引入）
 * @param model      可选模型 id（deepseek / qwen，迭代 7 模型选择器）；空=主模型
 * @param permission 迭代 11：前端权限快照（模式/工作区根），缺省时后端用默认工作区策略
 * @param credential 迭代 13：请求级自配模型凭据；与 model 同时出现视为冲突（400）
 * @param deepThinking 迭代 15：true=本轮开启深思（HIGH），false=默认快通道
 */
public record ChatRequest(String message, String sessionId, String model,
        FilePermissionPolicy permission, EphemeralCredential credential,
        MailConnectionSettings mailCredential, Boolean deepThinking) {

    public ChatRequest(String message, String sessionId, String model, FilePermissionPolicy permission) {
        this(message, sessionId, model, permission, null, null, false);
    }

    public ChatRequest(String message, String sessionId, String model, FilePermissionPolicy permission,
            EphemeralCredential credential) {
        this(message, sessionId, model, permission, credential, null, false);
    }

    public ChatRequest(String message, String sessionId, String model, FilePermissionPolicy permission,
            EphemeralCredential credential, MailConnectionSettings mailCredential) {
        this(message, sessionId, model, permission, credential, mailCredential, false);
    }
}
