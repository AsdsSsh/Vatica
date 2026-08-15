package com.example.vatica.permission;

import java.nio.file.Path;

import com.example.vatica.tool.FileToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 测试用沙盒构造：默认工作区根 = 传入的临时目录。 */
public final class TestFileSandbox {

    private TestFileSandbox() {
    }

    public static FileSandboxPolicy policy(Path root) {
        PermissionEventPublisher publisher = new PermissionEventPublisher(new ObjectMapper());
        FilePermissionRequestService requests = new FilePermissionRequestService(publisher);
        return new FileSandboxPolicy(requests, new FileToolProperties(root.toString(), 524288));
    }
}
