package com.example.vatica.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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

    private final Path workspaceDir;

    public FilesController(FileToolProperties properties) {
        this.workspaceDir = Path.of(properties.workspaceDir()).toAbsolutePath().normalize();
    }

    /** 工作目录内文件列表（按修改时间倒序，前端产物面板用）。 */
    @GetMapping
    public List<FileArtifactDto> list() throws IOException {
        if (!Files.isDirectory(workspaceDir)) {
            return List.of();
        }
        List<FileArtifactDto> files = new ArrayList<>();
        try (var stream = Files.list(workspaceDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
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

    /** 按文件名下载（inline，浏览器/系统默认程序直接打开）。 */
    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        final Path file;
        try {
            file = PathSecurityGuard.resolveForRead(workspaceDir, name);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }
}
