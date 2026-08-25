package com.example.vatica.context;

/** 迭代 29B：事实是否仍可作为当前上下文依据。 */
public enum ContextFactVerificationState {
    CURRENT,
    NEEDS_REFRESH,
    UNVERIFIABLE,
    REVOKED
}
