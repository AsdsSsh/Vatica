package com.example.vatica.controller;

/**
 * 迭代 31B：可追溯会话摘要段的层级。
 *
 * <p>当前摘要服务只写入 {@link #L1_LOCAL}。其余层级预留给后续按主题聚合和会话总览重建，
 * 不会替代 {@code vatica_chat_session.summaryText} 的既有兼容语义。</p>
 */
public enum ChatSummarySegmentLevel {

    /** 单个连续消息区间的不可变局部摘要。 */
    L1_LOCAL,

    /** 预留：由多个局部摘要归并得到的主题摘要。 */
    L2_TOPIC,

    /** 预留：可重建的会话总览快照。 */
    L3_OVERVIEW
}
