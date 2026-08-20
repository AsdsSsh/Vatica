package com.example.vatica.permission;

import java.util.Arrays;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.mail.MailConnectionSettings;
import com.example.vatica.mail.MailCredentialContext;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.example.vatica.tool.AgentToolProvider;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 把权限上下文绑定到每个工具调用（迭代 11）：
 * 调用前设置 {@link FilePermissionContext}，调用后清理——工具在哪个线程执行都正确。
 */
public final class PermissionBoundToolCallbacks {

    private PermissionBoundToolCallbacks() {
    }

    /** 迭代 22B：AgentScope 原生工具的身份、文件权限与临时邮件凭据绑定。 */
    public static AgentTool[] wrap(AgentToolProvider provider, FilePermissionPolicy policy,
            String channel, RequestIdentity identity, MailConnectionSettings mailCredential) {
        AgentTool[] base = provider == null ? new AgentTool[0] : provider.getAgentTools();
        return Arrays.stream(base).map(tool -> wrap(tool, policy, channel, identity, mailCredential))
                .toArray(AgentTool[]::new);
    }

    private static AgentTool wrap(AgentTool delegate, FilePermissionPolicy policy, String channel,
            RequestIdentity identity, MailConnectionSettings mailCredential) {
        return new AgentTool() {
            @Override public String getName() { return delegate.getName(); }
            @Override public String getDescription() { return delegate.getDescription(); }
            @Override public java.util.Map<String, Object> getParameters() { return delegate.getParameters(); }
            @Override public Boolean getStrict() { return delegate.getStrict(); }
            @Override public java.util.Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
            @Override public boolean isReadOnly() { return delegate.isReadOnly(); }

            @Override
            public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.defer(() -> {
                    RequestIdentityContext.set(identity);
                    FilePermissionContext.set(policy, channel);
                    MailCredentialContext.set(mailCredential);
                    return delegate.callAsync(param).doFinally(ignored -> {
                        FilePermissionContext.clear();
                        MailCredentialContext.clear();
                        RequestIdentityContext.clear();
                    });
                });
            }
        };
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
