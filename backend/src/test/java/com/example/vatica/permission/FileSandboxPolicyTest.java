package com.example.vatica.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import com.example.vatica.tool.FileToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 迭代 11：文件沙盒判定测试——工作区内放行 / 越界无订阅者拒绝 / 保护路径禁写。 */
class FileSandboxPolicyTest {

    @TempDir
    Path root;

    private FileSandboxPolicy policy;
    private FilePermissionRequestService requests;

    @BeforeEach
    void setUp() {
        PermissionEventPublisher publisher = new PermissionEventPublisher(new ObjectMapper());
        requests = new FilePermissionRequestService(publisher);
        policy = new FileSandboxPolicy(requests, new FileToolProperties(root.toString(), 1024));
    }

    @Test
    void readInsideDefaultRootAllowed() {
        FilePermissionContext.set(
                new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE,
                        List.of(new WorkspaceRoot(root.toString(), true, true))),
                null);
        try {
            assertThat(policy.resolveForRead("a.txt")).isEqualByComparingTo(root.resolve("a.txt"));
        } finally {
            FilePermissionContext.clear();
        }
    }

    @Test
    void writeOutsideRootWithoutSubscriberRejected() {
        Path outside = root.getParent().resolve("outside-" + System.nanoTime());
        FilePermissionContext.set(
                new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE,
                        List.of(new WorkspaceRoot(root.toString(), true, true))),
                "chat:test");
        try {
            assertThatThrownBy(() -> policy.resolveForWrite(outside.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("没有可接收权限弹窗");
        } finally {
            FilePermissionContext.clear();
        }
    }

    @Test
    void vaticaStateDirWriteAlwaysProtected() {
        FilePermissionContext.set(
                new FilePermissionPolicy(FilePermissionMode.DANGER_FULL_ACCESS, List.of()),
                null);
        try {
            assertThatThrownBy(() -> policy.resolveForWrite(root.resolve(".vatica").resolve("x").toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("受保护路径");
        } finally {
            FilePermissionContext.clear();
        }
    }

    @Test
    void relativePathUsesFirstWorkspaceRoot() {
        Path taskRoot = root.resolve("task");
        FilePermissionContext.set(
                new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE,
                        List.of(new WorkspaceRoot(taskRoot.toString(), true, true))),
                null);
        try {
            assertThat(policy.resolveForRead("a.txt")).isEqualByComparingTo(taskRoot.resolve("a.txt"));
        } finally {
            FilePermissionContext.clear();
        }
    }

    /** 迭代 12 I12-7：approve(remember) 后，同 channel 同路径不再二次弹窗。 */
    @Test
    void rememberedGrantAllowsSameChannelOutsideRoot() {
        Path outside = root.getParent().resolve("granted-" + System.nanoTime());
        requests.rememberGrant("chat:test", outside.toString(), FileAccess.WRITE);
        FilePermissionContext.set(
                new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE,
                        List.of(new WorkspaceRoot(root.toString(), true, true))),
                "chat:test");
        try {
            assertThat(policy.resolveForWrite(outside.toString())).isEqualByComparingTo(outside);
            // 只记住 WRITE，不放大到 READ
            requests.cancelChannel("chat:test");
            assertThatThrownBy(() -> policy.resolveForWrite(outside.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("没有可接收权限弹窗");
        } finally {
            FilePermissionContext.clear();
        }
    }

    /** 迭代 12 I12-7：临时授权不越过保护路径。 */
    @Test
    void rememberedGrantNeverBypassesProtectedPath() {
        Path protectedPath = root.resolve(".vatica").resolve("x");
        requests.rememberGrant("chat:test", protectedPath.toString(), FileAccess.WRITE);
        FilePermissionContext.set(
                new FilePermissionPolicy(FilePermissionMode.DANGER_FULL_ACCESS, List.of()),
                "chat:test");
        try {
            assertThatThrownBy(() -> policy.resolveForWrite(protectedPath.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("受保护路径");
        } finally {
            FilePermissionContext.clear();
        }
    }
}
