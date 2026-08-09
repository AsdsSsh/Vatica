package com.example.vatica.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 路径规范化与白名单校验（工具层安全边界核心）。
 *
 * <p>安全要点：
 * <ol>
 *   <li>逻辑路径先校验（{@link #isWithin}），相对路径的 {@code ../} 穿越、白名单外绝对路径直接拒绝</li>
 *   <li>目标存在时用 {@link Path#toRealPath()} 解析符号链接/junction 后二次校验，防链接逃逸</li>
 *   <li>目标不存在时对父目录做真实路径解析；写场景（{@link #resolveForWrite}）自动创建父目录后同样二次校验</li>
 *   <li>前缀匹配带路径分隔符边界（防 {@code data} 白名单被 {@code data_evil} 绕过），Windows 大小写不敏感</li>
 * </ol>
 *
 * <p>错误约定：非法路径抛 {@link IllegalArgumentException}，message 是给模型的指引文案
 * （工具异常时由 ToolCallingAdvisor 把 message 回传模型继续循环，模型会转述给用户）。
 */
public final class PathSecurityGuard {

    private PathSecurityGuard() {
    }

    /** 读/列目录场景：目标必须存在且位于白名单根内。 */
    public static Path resolveForRead(Path root, String rawPath) {
        return resolveWithin(root, rawPath, false);
    }

    /** 写场景：目标可不存在（父目录自动创建），最终路径必须位于白名单根内。 */
    public static Path resolveForWrite(Path root, String rawPath) {
        return resolveWithin(root, rawPath, true);
    }

    private static Path resolveWithin(Path root, String rawPath, boolean createParents) {
        String p = rawPath.trim().replace('\\', '/'); // Windows 反斜杠统一为正斜杠
        if (p.isEmpty()) {
            throw new IllegalArgumentException("操作失败：路径不能为空。");
        }
        Path candidate = looksAbsolute(p) ? Path.of(p) : root.resolve(p);
        Path normalized = candidate.normalize(); // 处理 a/../b、./ 等

        // 逻辑路径预检：相对路径穿越（../）与白名单外绝对路径都在这里被拦下
        if (!isWithin(root, normalized)) {
            throw new IllegalArgumentException(
                    "操作失败：路径不在已授权目录内。请告知用户：该路径未授权访问，建议将文件移动到工作目录（data/）后重试。");
        }

        Path canonical;
        if (Files.exists(normalized)) {
            canonical = realPath(normalized);
        } else {
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("操作失败：无法解析该路径。");
            }
            if (createParents) {
                try {
                    Files.createDirectories(parent); // 已通过预检，父目录只会创建在白名单内
                } catch (IOException e) {
                    throw new IllegalStateException("操作失败：无法创建父目录。" + e.getMessage(), e);
                }
            } else if (!Files.exists(parent)) {
                throw new IllegalArgumentException("操作失败：文件不存在。请先调用 list_files 确认路径。");
            }
            canonical = realPath(parent).resolve(normalized.getFileName());
        }

        // 真实路径二次校验：解符号链接/junction 后可能已逃出白名单
        if (!isWithin(root, canonical)) {
            throw new IllegalArgumentException(
                    "操作失败：路径不在已授权目录内（疑似目录穿越或符号链接逃逸）。请告知用户：该路径未授权访问，建议将文件移动到工作目录（data/）后重试。");
        }
        return canonical;
    }

    /** 前缀匹配 + 分隔符边界（防 data 匹配 data_evil）；Windows 大小写不敏感。 */
    static boolean isWithin(Path root, Path child) {
        String r = root.normalize().toAbsolutePath().toString().toLowerCase(Locale.ROOT);
        String c = child.normalize().toAbsolutePath().toString().toLowerCase(Locale.ROOT);
        return c.equals(r) || c.startsWith(r + Path.of(".").toAbsolutePath().getFileSystem().getSeparator());
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：无法解析路径。" + e.getMessage(), e);
        }
    }

    private static boolean looksAbsolute(String p) {
        return p.startsWith("/") || p.matches("^[a-zA-Z]:.*");
    }
}
