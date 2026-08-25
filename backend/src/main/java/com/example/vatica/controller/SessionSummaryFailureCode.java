package com.example.vatica.controller;

/** 迭代 29A：会话摘要失败只记录脱敏分类，不持久化上游原始错误文本。 */
public enum SessionSummaryFailureCode {
    NONE,
    EMPTY_RESPONSE,
    TIMEOUT,
    TRANSIENT,
    CONFIGURATION,
    UNKNOWN
}
