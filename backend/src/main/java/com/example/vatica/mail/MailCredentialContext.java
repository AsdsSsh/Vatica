package com.example.vatica.mail;

/** EPHEMERAL 邮件密码只在单次 Agent 工具调用链路中存在。 */
public final class MailCredentialContext {
    private static final ThreadLocal<MailConnectionSettings> CURRENT = new ThreadLocal<>();
    private MailCredentialContext() { }
    public static void set(MailConnectionSettings settings) {
        if (settings == null) clear(); else CURRENT.set(settings);
    }
    public static MailConnectionSettings current() { return CURRENT.get(); }
    public static <T> T callWith(MailConnectionSettings settings, java.util.function.Supplier<T> action) {
        MailConnectionSettings previous = CURRENT.get();
        set(settings);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }
    public static void clear() { CURRENT.remove(); }
}
