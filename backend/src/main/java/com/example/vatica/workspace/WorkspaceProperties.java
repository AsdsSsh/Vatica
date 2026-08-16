package com.example.vatica.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 云工作区物理根；实际用户根固定为 base-dir/{orgId}/{userId}/。 */
@ConfigurationProperties(prefix = "vatica.workspace")
public record WorkspaceProperties(String baseDir) {
    public WorkspaceProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = "./workspace";
        }
    }
}
