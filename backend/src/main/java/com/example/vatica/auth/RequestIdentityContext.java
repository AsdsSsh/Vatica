package com.example.vatica.auth;

/** 请求身份上下文（迭代 13 I13-2）：ThreadLocal，afterCompletion 清理。 */
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

    public static void clear() {
        CURRENT.remove();
    }
}
