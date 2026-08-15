package com.example.vatica.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 迭代 11 I11-1：旧 {@code data/} 目录迁移器。
 *
 * <p>在 Spring 容器启动前（{@link com.example.vatica.VaticaApplication#main}）执行，
 * 保证 H2 数据库文件在 JPA 初始化前落到新位置。规则：
 * <ul>
 *   <li>内部状态文件（calendar/todos/models/H2）→ {@code .vatica/}</li>
 *   <li>其余历史产物（Word/Excel/笔记等）→ 工作区根（Codex 语义：工作区就是启动目录）</li>
 *   <li>迁移完成后删除旧 {@code data/} 目录</li>
 * </ul>
 */
public final class AppStateMigration {

    private static final Set<String> INTERNAL_FILES = Set.of(
            "calendar.ics", "todos.json", "models.json",
            "vatica-db.mv.db", "vatica-db.trace.db");

    private AppStateMigration() {
    }

    /** 执行迁移；幂等（data 不存在时直接返回）。 */
    public static void run(Path cwd, Path oldDataDir, Path stateDir) throws IOException {
        Path oldRoot = oldDataDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(oldRoot)) {
            return;
        }
        Path workspaceRoot = cwd.toAbsolutePath().normalize();
        Path stateRoot = stateDir.toAbsolutePath().normalize();
        Files.createDirectories(stateRoot);

        try (Stream<Path> entries = Files.list(oldRoot)) {
            for (Path entry : entries.toList()) {
                Path target = INTERNAL_FILES.contains(entry.getFileName().toString())
                        ? stateRoot.resolve(entry.getFileName().toString())
                        : workspaceRoot.resolve(entry.getFileName().toString());
                moveUnique(entry, target);
            }
        }

        // 全部迁出后删除旧目录（含可能残留的空子目录）
        try (Stream<Path> walk = Files.walk(oldRoot)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** 目标已存在时自动加后缀，避免覆盖用户数据。 */
    static Path moveUnique(Path source, Path target) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        }
        String base = target.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path candidate = target.resolveSibling(stem + "-迁移" + i + ext);
            if (!Files.exists(candidate)) {
                Files.createDirectories(candidate.getParent());
                Files.move(source, candidate, StandardCopyOption.ATOMIC_MOVE);
                return candidate;
            }
        }
        throw new IOException("无法为迁移目标生成唯一文件名：" + target);
    }
}
