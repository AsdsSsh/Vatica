package com.example.vatica.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;

/** 迭代 14：本地磁盘实现，先拼租户根再做路径与符号链接校验。 */
@Service
public class LocalIsolatedWorkspaceStore implements WorkspaceStore {

    private final Path baseDir;

    public LocalIsolatedWorkspaceStore(WorkspaceProperties properties) {
        this.baseDir = Path.of(properties.baseDir()).toAbsolutePath().normalize();
    }

    @Override
    public Path root(RequestIdentity identity) {
        requireIdentity(identity);
        Path root = baseDir.resolve(String.valueOf(identity.orgId()))
                .resolve(String.valueOf(identity.userId())).normalize();
        if (!root.startsWith(baseDir)) {
            throw new IllegalArgumentException("操作失败：工作区租户前缀不合法。");
        }
        try {
            Files.createDirectories(root);
            return root.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：无法初始化用户工作区。" + e.getMessage(), e);
        }
    }

    @Override
    public List<Entry> list(RequestIdentity identity, String relativePath) throws IOException {
        Path root = root(identity);
        Path directory = resolve(root, relativePath, true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("操作失败：目录不存在。");
        }
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path item : stream) {
                if (Files.isSymbolicLink(item)) {
                    continue;
                }
                result.add(new Entry(toUnix(root.relativize(item)),
                        Files.isDirectory(item) ? 0L : Files.size(item), Files.isDirectory(item),
                        Files.getLastModifiedTime(item).toInstant().toString()));
            }
        }
        result.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::path));
        return result;
    }

    @Override
    public Path write(RequestIdentity identity, String relativePath, InputStream content) throws IOException {
        Path root = root(identity);
        Path target = resolve(root, relativePath, false);
        if (target.equals(root)) {
            throw new IllegalArgumentException("操作失败：文件名不能为空。");
        }
        Files.createDirectories(target.getParent());
        rejectSymlinkSegments(root, target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    @Override
    public Path read(RequestIdentity identity, String relativePath) throws IOException {
        Path target = resolve(root(identity), relativePath, true);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("操作失败：文件不存在。");
        }
        return target;
    }

    @Override
    public void delete(RequestIdentity identity, String relativePath) throws IOException {
        Path root = root(identity);
        Path target = resolve(root, relativePath, true);
        if (target.equals(root)) {
            throw new IllegalArgumentException("操作失败：不能删除工作区根目录。");
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
                if (children.iterator().hasNext()) {
                    throw new IllegalArgumentException("操作失败：只能删除空目录。");
                }
            }
        }
        Files.deleteIfExists(target);
    }

    private static Path resolve(Path root, String relativePath, boolean mustExist) throws IOException {
        String value = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        Path relative = Path.of(value.isBlank() ? "." : value);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("操作失败：工作区 API 只接受相对路径。");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("操作失败：路径越过用户工作区边界。");
        }
        rejectSymlinkSegments(root, mustExist ? target : target.getParent());
        return target;
    }

    private static void rejectSymlinkSegments(Path root, Path target) throws IOException {
        if (target == null) {
            return;
        }
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("操作失败：工作区路径不能经过符号链接。");
            }
        }
    }

    private static String toUnix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void requireIdentity(RequestIdentity identity) {
        if (identity == null || identity.userId() == null || identity.orgId() == null) {
            throw new IllegalStateException("操作失败：缺少工作区租户身份。");
        }
    }
}
