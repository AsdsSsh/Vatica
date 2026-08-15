package com.example.vatica.controller;

/**
 * 文件产物条目（迭代 7 I7-3；迭代 9 I9-3 契约显式化）。
 *
 * @param absolutePath 工作目录内绝对路径（前端"打开文件"用）
 */
public record FileArtifactDto(String name, long size, String modifiedAt, String absolutePath) {
}
