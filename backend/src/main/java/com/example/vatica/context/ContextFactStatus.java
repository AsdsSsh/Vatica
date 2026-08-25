package com.example.vatica.context;

/** 迭代 29B：事实版本生命周期。事实版本只新增，不原地覆盖。 */
public enum ContextFactStatus {
    ACTIVE,
    SUPERSEDED,
    REVOKED
}
