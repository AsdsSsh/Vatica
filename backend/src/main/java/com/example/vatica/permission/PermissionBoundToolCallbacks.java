package com.example.vatica.permission;

import java.util.Arrays;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.mail.MailConnectionSettings;
import com.example.vatica.mail.MailCredentialContext;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把权限上下文绑定到每个工具调用（迭代 11）：
 * 调用前设置 {@link FilePermissionContext}，调用后清理——工具在哪个线程执行都正确。
 */
public final class PermissionBoundToolCallbacks {

    private PermissionBoundToolCallbacks() {
    }

    public static ToolCallback[] wrap(ToolCallbackProvider provider, FilePermissionPolicy policy,
            String channel) {
        return wrap(provider, policy, channel, RequestIdentityContext.require());
    }

    /** 把调用入口捕获的身份快照带到 Spring AI 可能切换的工具线程。 */
    public static ToolCallback[] wrap(ToolCallbackProvider provider, FilePermissionPolicy policy,
            String channel, RequestIdentity identity) {
        return wrap(provider, policy, channel, identity, null);
    }

    public static ToolCallback[] wrap(ToolCallbackProvider provider, FilePermissionPolicy policy,
            String channel, RequestIdentity identity, MailConnectionSettings mailCredential) {
        ToolCallback[] base = provider.getToolCallbacks();
        return Arrays.stream(base)
                .map(callback -> wrap(callback, policy, channel, identity, mailCredential))
                .toArray(ToolCallback[]::new);
    }

    private static ToolCallback wrap(ToolCallback delegate, FilePermissionPolicy policy, String channel,
            RequestIdentity identity, MailConnectionSettings mailCredential) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                return RequestIdentityContext.callWith(identity, () -> {
                    FilePermissionContext.set(policy, channel);
                    try {
                        return MailCredentialContext.callWith(mailCredential, () -> delegate.call(toolInput));
                    } finally {
                        FilePermissionContext.clear();
                    }
                });
            }
        };
    }
}
