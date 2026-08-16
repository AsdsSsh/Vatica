package com.example.vatica.auth;

/** 请求身份上下文（迭代 13 I13-2；迭代 14 I14-1 增加异步快照恢复）。 */
public final class RequestIdentityContext {

    private static final ThreadLocal<RequestIdentity> CURRENT = new ThreadLocal<>();

    private RequestIdentityContext() {
    }

    public static void set(RequestIdentity identity) {
        CURRENT.set(identity);
    }

    public static RequestIdentity current() {
        return CURRENT.get();
    }

    /** 业务数据访问必须有明确身份，禁止在缺失上下文时静默退化为公共用户。 */
    public static RequestIdentity require() {
        RequestIdentity identity = CURRENT.get();
        if (identity == null || identity.userId() == null || identity.orgId() == null) {
            throw new IllegalStateException("操作失败：当前执行链路缺少用户身份，请重新登录后重试。");
        }
        return identity;
    }

    /** 在异步线程恢复请求快照；结束后恢复原上下文，避免虚拟线程复用时串租户。 */
    public static <T> T callWith(RequestIdentity identity, java.util.function.Supplier<T> action) {
        RequestIdentity previous = CURRENT.get();
        set(identity);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
