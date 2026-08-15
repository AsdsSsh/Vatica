package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用内部状态目录（{@code vatica.app.*}，迭代 11）：日历/待办/模型配置/权限相关
 * 内部数据统一存放位置，与 Agent 的工作区（workspace roots）解耦。
 *
 * <p>默认 {@code ./.vatica}——迭代 11 起取代旧 {@code ./data}。
 *
 * @param stateDir 内部状态目录（相对路径以进程工作目录为基准）
 */
@ConfigurationProperties(prefix = "vatica.app")
public record AppStateProperties(String stateDir) {

    public static final String DEFAULT_STATE_DIR = "./.vatica";

    public AppStateProperties {
        if (stateDir == null || stateDir.isBlank()) {
            stateDir = DEFAULT_STATE_DIR;
        }
    }
}
