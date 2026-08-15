package com.example.vatica.permission;

import java.util.Arrays;

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
        ToolCallback[] base = provider.getToolCallbacks();
        return Arrays.stream(base)
                .map(callback -> wrap(callback, policy, channel))
                .toArray(ToolCallback[]::new);
    }

    private static ToolCallback wrap(ToolCallback delegate, FilePermissionPolicy policy, String channel) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                FilePermissionContext.set(policy, channel);
                try {
                    return delegate.call(toolInput);
                } finally {
                    FilePermissionContext.clear();
                }
            }
        };
    }
}
