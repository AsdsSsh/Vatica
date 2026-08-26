package com.example.vatica.context;

/**
 * 迭代 31A：请求期上下文使用强度。
 *
 * <p>模式是预算意图，而不是模型能力声明。运行时仍会依据模型窗口把不适合的模式降级，
 * 因此调用方不能只因为模型支持大窗口就默认回灌全部历史。</p>
 */
public enum ContextMode {
    NORMAL,
    LONG_TASK,
    DEEP_REVIEW;

    public static ContextMode normalize(ContextMode mode) {
        return mode == null ? NORMAL : mode;
    }

    /** 当前模型窗口不足时的保守降级路径。 */
    public ContextMode fallback() {
        return switch (this) {
            case DEEP_REVIEW -> LONG_TASK;
            case LONG_TASK, NORMAL -> NORMAL;
        };
    }
}
