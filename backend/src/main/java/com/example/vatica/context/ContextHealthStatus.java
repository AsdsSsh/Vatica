package com.example.vatica.context;

/** 迭代 29D：对用户可见的上下文健康状态，不暴露内部 Prompt 或原始内容。 */
public enum ContextHealthStatus {
    HEALTHY,
    PROCESSING,
    DEGRADED,
    NEEDS_REFRESH
}
