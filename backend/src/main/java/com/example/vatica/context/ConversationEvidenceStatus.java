package com.example.vatica.context;

/** 迭代 31C：会话原文证据检索结果，供可观测性区分“没命中”和“没有执行”。 */
public enum ConversationEvidenceStatus {
    MATCHED,
    NO_MATCH,
    DISABLED,
    /** 普通问答不访问旧原文；这不是一次检索失败。 */
    SKIPPED_MODE,
    SKIPPED_NO_HISTORY,
    SKIPPED_BUDGET,
    NOT_OWNED,
    UNAVAILABLE
}
