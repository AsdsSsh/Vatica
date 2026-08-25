package com.example.vatica.context;

/** 迭代 29B：事实可信等级；Agent 推断不能直接成为当前事实。 */
public enum ContextFactTrustLevel {
    USER_CONFIRMED,
    SYSTEM_VERIFIED,
    TOOL_OBSERVED,
    AGENT_DERIVED
}
