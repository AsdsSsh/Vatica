package com.example.vatica.controller;

import com.example.vatica.config.EphemeralCredential;
import com.example.vatica.context.ContextMode;
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
 * @param contextMode 迭代 31D：NORMAL / LONG_TASK / DEEP_REVIEW，请求会按模型窗口保守降级
 * @param taskId      迭代 33：可选关联任务 ID；服务端据此读取受控的工具、审批和交付物事实
 */
public record ChatRequest(String message, String sessionId, String model,
        FilePermissionPolicy permission, EphemeralCredential credential,
        MailConnectionSettings mailCredential, Boolean deepThinking, ContextMode contextMode, String taskId) {

    public ChatRequest {
        contextMode = ContextMode.normalize(contextMode);
        taskId = taskId == null || taskId.isBlank() ? null : taskId.trim();
    }

    /** 兼容迭代 31D 之前的完整请求构造器。 */
    public ChatRequest(String message, String sessionId, String model,
            FilePermissionPolicy permission, EphemeralCredential credential,
            MailConnectionSettings mailCredential, Boolean deepThinking, ContextMode contextMode) {
        this(message, sessionId, model, permission, credential, mailCredential, deepThinking, contextMode, null);
    }

    /** 兼容迭代 15～31C 的完整请求构造器。 */
    public ChatRequest(String message, String sessionId, String model,
            FilePermissionPolicy permission, EphemeralCredential credential,
            MailConnectionSettings mailCredential, Boolean deepThinking) {
        this(message, sessionId, model, permission, credential, mailCredential, deepThinking, ContextMode.NORMAL, null);
    }

    public ChatRequest(String message, String sessionId, String model, FilePermissionPolicy permission) {
        this(message, sessionId, model, permission, null, null, false, ContextMode.NORMAL, null);
    }

    public ChatRequest(String message, String sessionId, String model, FilePermissionPolicy permission,
            EphemeralCredential credential) {
        this(message, sessionId, model, permission, credential, null, false, ContextMode.NORMAL, null);
    }

    public ChatRequest(String message, String sessionId, String model, FilePermissionPolicy permission,
            EphemeralCredential credential, MailConnectionSettings mailCredential) {
        this(message, sessionId, model, permission, credential, mailCredential, false, ContextMode.NORMAL, null);
    }
}
