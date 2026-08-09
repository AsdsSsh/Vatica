package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 路径安全核心单测：目录穿越、前缀边界、大小写、符号链接逃逸。
 *
 * <p>符号链接用例在无权限环境（如 Windows 未开开发者模式）用 assumeTrue 降级跳过，
 * 保证任何环境下 mvn test 全绿。
 */
class PathSecurityGuardTest {

    @TempDir
    Path root;

    /** 相对路径在根内 → 返回规范化路径 */
    @Test
    void resolvesRelativePathInsideRoot() throws IOException {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/a.txt"), "hello");

        Path resolved = PathSecurityGuard.resolveForRead(root, "sub/a.txt");

        assertThat(resolved).isEqualTo(root.resolve("sub/a.txt").toRealPath());
    }

    /** 上级目录穿越（../）被拒绝 */
    @Test
    void rejectsParentDirectoryTraversal() {
        assertThatThrownBy(() -> PathSecurityGuard.resolveForRead(root, "../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已授权");
    }

    /** 多层穿越（a/../../x）被拒绝 */
    @Test
    void rejectsMultiLevelTraversal() {
        assertThatThrownBy(() -> PathSecurityGuard.resolveForRead(root, "a/../../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已授权");
    }

    /** 白名单外绝对路径被拒绝（模拟 C:/Windows/win.ini 场景） */
    @Test
    void rejectsAbsolutePathOutsideWhitelist() {
        Path outside = root.getParent().resolve("outside.txt");
        assertThatThrownBy(() -> PathSecurityGuard.resolveForRead(root, outside.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已授权");
    }

    /** 前缀边界伪造（data 白名单被 data2 绕过）被拒绝——纯字符串级用例 */
    @Test
    void rejectsPrefixBoundarySpoofing() throws IOException {
        Path sibling = root.getParent().resolve(root.getFileName() + "2");
        Files.createDirectories(sibling);

        assertThat(PathSecurityGuard.isWithin(root, sibling.resolve("a.txt"))).isFalse();
        assertThat(PathSecurityGuard.isWithin(root, sibling)).isFalse();
    }

    /** 根目录自身放行（列根目录场景） */
    @Test
    void allowsRootItself() {
        assertThat(PathSecurityGuard.isWithin(root, root)).isTrue();
    }

    /** Windows 大小写不敏感：传入反转大小写的路径仍放行 */
    @Test
    void acceptsCaseInsensitivePaths() throws IOException {
        Path real = Files.createTempFile(root, "CaseTest", ".txt");
        String swapped = swapCase(real.toString());
        Path resolved = PathSecurityGuard.resolveForRead(root, swapped);
        assertThat(resolved).isEqualTo(real.toRealPath());
    }

    /** normalize 处理 a/../b 后仍落在根内 */
    @Test
    void normalizedPathStaysInsideRoot() throws IOException {
        Files.createDirectories(root.resolve("sub"));
        Path resolved = PathSecurityGuard.resolveForRead(root, "sub/../sub");
        assertThat(resolved).isEqualTo(root.resolve("sub").toRealPath());
    }

    /** 空路径与纯空白被拒绝 */
    @Test
    void rejectsEmptyAndBlankPaths() {
        assertThatThrownBy(() -> PathSecurityGuard.resolveForRead(root, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> PathSecurityGuard.resolveForRead(root, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    /** 符号链接逃逸：root 内链接指向根外目录 → 拒绝（无权限环境跳过） */
    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path outsideDir = Files.createTempDirectory("outside");
        try {
            Files.writeString(outsideDir.resolve("secret.txt"), "top-secret");
            Path link = root.resolve("link");
            try {
                Files.createSymbolicLink(link, outsideDir);
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "当前环境无符号链接创建权限，跳过该用例");
                return;
            }

            assertThatThrownBy(() -> PathSecurityGuard.resolveForRead(root, "link/secret.txt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("已授权");
        } finally {
            // JUnit 的 @TempDir 清理不会删除链接指向的外部目标，这里显式清理，避免残留
            deleteRecursively(outsideDir);
        }
    }

    /** 递归删除目录（仅用于清理测试自建的外部临时目录） */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** 写场景：自动创建父目录且最终路径仍在根内 */
    @Test
    void writeCreatesParentsWithinRoot() throws IOException {
        Path resolved = PathSecurityGuard.resolveForWrite(root, "新目录/子目录/a.txt");
        // 目标文件尚未创建，toRealPath 不可用；比较规范化绝对路径（Windows 大小写不敏感）
        assertThat(resolved.normalize().toAbsolutePath().toString().toLowerCase())
                .isEqualTo(root.resolve("新目录/子目录/a.txt").normalize().toAbsolutePath().toString().toLowerCase());
        assertThat(Files.isDirectory(root.resolve("新目录/子目录"))).isTrue();
    }

    /** 写场景：穿越被拒绝且根外不产生任何文件 */
    @Test
    void writeRejectsTraversalWithoutCreatingFile() {
        assertThatThrownBy(() -> PathSecurityGuard.resolveForWrite(root, "../evil.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已授权");
        assertThat(Files.exists(root.getParent().resolve("evil.txt"))).isFalse();
    }

    /** 反转字母大小写（模拟 Windows 用户输入大小写差异） */
    private static String swapCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append(Character.toLowerCase(c));
            } else if (Character.isLowerCase(c)) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
