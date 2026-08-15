package com.example.vatica.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文件沙盒策略（迭代 11）：按请求携带的权限快照机械判定 allow / ask / deny。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>保护路径（.vatica 永远禁写；.git/.agents/.codex 在非完全访问模式下禁写）</li>
 *   <li>danger-full-access → 放行（.vatica 除外）</li>
 *   <li>命中工作区根 → 按模式与根读写开关判定</li>
 *   <li>未命中 → on-request：有 UI 通道则阻塞请求用户，无 UI（MCP）直接拒绝</li>
 * </ol>
 *
 * <p>路径安全：目标存在时用真实路径参与匹配；不存在时解析父目录真实路径后拼接，
 * 防符号链接逃逸与大小写/前缀混淆。
 *
 * <p>Bean 由 {@code ToolConfig.fileSandboxPolicy(...)} 显式装配（迭代 11 修正 IDEA 对
 * 跨包组件扫描的误报，也把"沙盒策略由工具层装配"表达得更直白）。
 */
public class FileSandboxPolicy {

    private static final Set<String> ALWAYS_PROTECTED = Set.of(".vatica");
    private static final Set<String> PROTECTED_NAMES = Set.of(".git", ".agents", ".codex", ".vatica");

    private final Path defaultRoot;
    private final FilePermissionRequestService requestService;

    public FileSandboxPolicy(FilePermissionRequestService requestService,
            com.example.vatica.tool.FileToolProperties fileProps) {
        this.requestService = requestService;
        this.defaultRoot = Path.of(fileProps.workspaceDir()).toAbsolutePath().normalize();
    }

    public Path resolveForRead(String rawPath) {
        return resolve(rawPath, FileAccess.READ, "read_file / list_files 需要读取该路径");
    }

    public Path resolveForWrite(String rawPath) {
        return resolve(rawPath, FileAccess.WRITE, "write_file / 文档生成需要写入该路径");
    }

    public Path resolveForRead(String rawPath, String description) {
        return resolve(rawPath, FileAccess.READ, description);
    }

    public Path resolveForWrite(String rawPath, String description) {
        return resolve(rawPath, FileAccess.WRITE, description);
    }

    /** 当前请求生效的策略（无上下文时返回后端默认工作区策略）。 */
    public FilePermissionPolicy currentPolicy() {
        FilePermissionContext.Snapshot context = FilePermissionContext.current();
        return context == null || context.policy() == null
                ? FilePermissionPolicy.defaultPolicy(defaultRoot)
                : context.policy().normalized();
    }

    private Path resolve(String rawPath, FileAccess access, String description) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("操作失败：路径不能为空。");
        }
        FilePermissionContext.Snapshot context = FilePermissionContext.current();
        FilePermissionPolicy policy = context == null || context.policy() == null
                ? FilePermissionPolicy.defaultPolicy(defaultRoot)
                : context.policy().normalized();
        String channel = context == null ? null : context.channel();

        Path candidate = toCandidate(rawPath, policy);
        Path real = realOrParent(candidate);

        if (isProtected(real, access, policy.mode())) {
            throw new IllegalArgumentException("操作失败：该路径是受保护路径（" + real
                    + "），不允许通过文件工具修改。请改用其他路径。");
        }

        if (policy.mode() == FilePermissionMode.DANGER_FULL_ACCESS) {
            return prepareForWrite(real, access);
        }

        WorkspaceRoot matched = matchRoot(real, policy.workspaceRoots());
        if (matched != null && allows(matched, access, policy.mode())) {
            return prepareForWrite(real, access);
        }
        // allow 不满足 → on-request；无 UI 通道时 request 内部会直接按拒绝抛错
        requestService.request(real, access, policy, channel, description);
        return prepareForWrite(real, access);
    }

    private static Path prepareForWrite(Path path, FileAccess access) {
        if (access != FileAccess.WRITE) {
            return path;
        }
        Path parent = path.getParent();
        if (parent == null) {
            return path;
        }
        try {
            Files.createDirectories(parent);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：无法创建父目录。" + e.getMessage(), e);
        }
    }

    private Path toCandidate(String rawPath, FilePermissionPolicy policy) {
        Path raw = Path.of(rawPath.trim());
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        Path base = defaultRoot;
        if (policy.workspaceRoots() != null && !policy.workspaceRoots().isEmpty()) {
            base = Path.of(policy.workspaceRoots().get(0).path());
        }
        return base.resolve(raw).normalize();
    }

    private static Path realOrParent(Path candidate) {
        try {
            if (Files.exists(candidate)) {
                return candidate.toRealPath();
            }
            Path parent = candidate.getParent();
            if (parent == null) {
                return candidate.toAbsolutePath().normalize();
            }
            return parent.toRealPath().resolve(candidate.getFileName());
        } catch (IOException e) {
            return candidate.toAbsolutePath().normalize();
        }
    }

    private static boolean isProtected(Path real, FileAccess access, FilePermissionMode mode) {
        if (access != FileAccess.WRITE) {
            return false;
        }
        for (Path part : real) {
            String name = part.getFileName().toString().toLowerCase(Locale.ROOT);
            if (ALWAYS_PROTECTED.contains(name)) {
                return true;
            }
            if (mode != FilePermissionMode.DANGER_FULL_ACCESS && PROTECTED_NAMES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static WorkspaceRoot matchRoot(Path real, List<WorkspaceRoot> roots) {
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        String target = pathKey(real);
        WorkspaceRoot best = null;
        int bestLen = -1;
        for (WorkspaceRoot root : roots) {
            String rootKey = pathKey(Path.of(root.path()));
            if (target.equals(rootKey) || target.startsWith(rootKey + sep())) {
                if (rootKey.length() > bestLen) {
                    best = root;
                    bestLen = rootKey.length();
                }
            }
        }
        return best;
    }

    private static boolean allows(WorkspaceRoot root, FileAccess access, FilePermissionMode mode) {
        if (mode == FilePermissionMode.READ_ONLY && access == FileAccess.WRITE) {
            return false;
        }
        return access == FileAccess.READ ? root.read() : root.write();
    }

    private static String pathKey(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private static String sep() {
        return Path.of(".").toAbsolutePath().getFileSystem().getSeparator();
    }
}
