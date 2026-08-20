package com.example.vatica.controller;

/**
 * 模型清单项（迭代 7 模型选择器；迭代 9 I9-3 契约显式化：原 Map 投影改类型化 DTO，
 * OpenAPI schema 因此可读）。
 *
 * @param configured 槽位是否可调用（启用且已有 API Key，或指向本地模型端点）
 */
public record ModelInfoDto(String id, String name, boolean configured) {
}
