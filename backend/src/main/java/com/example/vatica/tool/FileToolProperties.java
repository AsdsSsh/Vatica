package com.example.vatica.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件工具白名单配置（{@code vatica.tool.file.*}）。
 *
 * <p>前缀使用业务自定义的 {@code vatica.*}，避免与框架配置混淆，
 * 避免与框架配置混淆（迭代 1 已踩过 Spring AI 配置键弃用坑）。
 */
@ConfigurationProperties(prefix = "vatica.tool.file")
public record FileToolProperties(String workspaceDir, long maxReadSizeBytes) {

    /** 单次 read_file 最大读取字节数默认值：512KB，防止大文件烧 token / 撑爆上下文。 */
    public static final long DEFAULT_MAX_READ_SIZE_BYTES = 524288;

    public FileToolProperties {
        if (workspaceDir == null || workspaceDir.isBlank()) {
            throw new IllegalArgumentException("vatica.tool.file.workspace-dir 不能为空");
        }
        if (maxReadSizeBytes <= 0) {
            maxReadSizeBytes = DEFAULT_MAX_READ_SIZE_BYTES;
        }
    }
}
