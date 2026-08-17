package com.example.vatica.trace;

/** 迭代 15 I15-7：当前线程最近一次 LLM 思考内容（Executor 执行步骤后由编排层取走归档）。 */
public final class ReasoningContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ReasoningContext() {
    }

    public static void set(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(reasoning);
        }
    }

    public static String take() {
        String value = CURRENT.get();
        CURRENT.remove();
        return value;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
