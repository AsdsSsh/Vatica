package com.example.vatica.permission;

/**
 * 当前工具调用所属的权限上下文（迭代 11）。
 *
 * <p>由 PermissionBoundToolCallback 在每次工具调用前设置、调用后清理，
 * 因此无论工具在哪个线程执行都能拿到正确的策略与事件通道。
 */
public final class FilePermissionContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    /** @param channel 权限事件通道：{@code task:<id>} 或 {@code chat:<sessionId>}；MCP/无 UI 为 null。 */
    public record Snapshot(FilePermissionPolicy policy, String channel) {
    }

    private FilePermissionContext() {
    }

    public static void set(FilePermissionPolicy policy, String channel) {
        CURRENT.set(new Snapshot(policy == null ? null : policy.normalized(), channel));
    }

    public static Snapshot current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
