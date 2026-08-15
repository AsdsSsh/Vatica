package com.example.vatica.controller;

/**
 * 模型清单项（迭代 7 模型选择器；迭代 9 I9-3 契约显式化：原 Map 投影改类型化 DTO，
 * OpenAPI schema 因此可读）。
 *
 * @param configured 槽位是否启用（未启用在前端置灰）
 */
public record ModelInfoDto(String id, String name, boolean configured) {
}
