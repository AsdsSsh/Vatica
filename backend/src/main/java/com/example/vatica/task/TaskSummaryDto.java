package com.example.vatica.task;

/**
 * 任务概要（最近任务列表用；迭代 9 I9-3 契约显式化：原 Map 投影改类型化 DTO）。
 */
public record TaskSummaryDto(String id, String goal, String status, String createdAt, String benchmarkCaseId) {
}
