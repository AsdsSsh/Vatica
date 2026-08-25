package com.example.vatica.controller;

/**
 * 迭代 29A：会话摘要的可信状态。
 *
 * <p>摘要是可重建缓存，不是会话原文的事实源。只有 SUCCESS 可以说明
 * {@code summaryThroughSeq} 已被当前摘要覆盖；PENDING/FAILED 都要求上下文组装器
 * 保留未摘要历史的受控降级片段。</p>
 */
public enum SessionSummaryStatus {
    PENDING,
    SUCCESS,
    FAILED
}
