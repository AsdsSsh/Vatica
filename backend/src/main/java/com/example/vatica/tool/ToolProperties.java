package com.example.vatica.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具层通用配置（{@code vatica.tool.*}，迭代 2.5 新增）。
 *
 * @param maxCallsPerRequest 单次请求工具调用次数上限，防模型死循环烧 token（0/负数回退默认值）。
 */
@ConfigurationProperties(prefix = "vatica.tool")
public record ToolProperties(int maxCallsPerRequest) {

    public static final int DEFAULT_MAX_CALLS_PER_REQUEST = 20;

    public ToolProperties {
        if (maxCallsPerRequest <= 0) {
            maxCallsPerRequest = DEFAULT_MAX_CALLS_PER_REQUEST;
        }
    }
}
