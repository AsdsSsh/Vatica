package com.example.vatica.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.vatica.tool.FileToolProperties;
import com.example.vatica.tool.PathSecurityGuard;

/**
 * 文件产物接口（迭代 7 I7-3）：工作目录（白名单 workspace-dir）内文件列表 + 下载。
 * 前端"产物列表 + 打开文件"用；路径安全复用文件工具同一套白名单与防穿越校验。
 */
@RestController
@RequestMapping("/api/files")
public class FilesController {

    /**
     * 内部数据文件（迭代 10 V12）：文件产物面板只展示交付产物，
     * 模型配置/待办/日历/H2 数据文件仍可由 file 工具读写，但不在产物列表暴露。
     */
    private static final Set<String> INTERNAL_FILES = Set.of(
            "models.json", "todos.json", "calendar.ics", "vatica-db.mv.db", "vatica-db.trace.db");

    private final Path workspaceDir;

    public FilesController(FileToolProperties properties) {
        this.workspaceDir = Path.of(properties.workspaceDir()).toAbsolutePath().normalize();
    }

    /** 工作目录内文件列表（按修改时间倒序，前端产物面板用；排除内部数据文件）。 */
    @GetMapping
    public List<FileArtifactDto> list() throws IOException {
        if (!Files.isDirectory(workspaceDir)) {
            return List.of();
        }
        List<FileArtifactDto> files = new ArrayList<>();
        try (var stream = Files.list(workspaceDir)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(p -> !INTERNAL_FILES.contains(p.getFileName().toString()))
                    .toList()) {
                files.add(new FileArtifactDto(
                        p.getFileName().toString(),
                        Files.size(p),
                        Files.getLastModifiedTime(p).toInstant().toString(),
                        p.toString()));
            }
        }
        files.sort(Comparator.comparing(FileArtifactDto::modifiedAt).reversed());
        return files;
    }

    /**
     * 按文件名下载（inline，浏览器/系统默认程序直接打开）。
     * 迭代 10 I10-7：错误响应与全局契约一致，统一返回 {@code {"message": ...}}。
     */
    @GetMapping("/{name}")
    public ResponseEntity<?> download(@PathVariable String name) {
        final Path file;
        try {
            file = PathSecurityGuard.resolveForRead(workspaceDir, name);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
        }
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError("操作失败：文件不存在（" + name + "）。"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }
}
