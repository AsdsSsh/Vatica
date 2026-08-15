package com.example.vatica.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

import com.example.vatica.permission.FileSandboxPolicy;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 文件读写工具（read_file / write_file / list_files）。
 *
 * <p>迭代 11：路径判定从单一 data/ 白名单改为 {@link FileSandboxPolicy}——
 * 工作区内按模式自动放行、越界 on-request 用户确认。
 *
 * <p>错误约定（对工具调用循环敏感）：
 * <ul>
 *   <li>参数非法/文件不存在/未授权/超限 → 抛 {@link IllegalArgumentException}，message 是给模型的指引文案；
 *       工具异常时由 ToolCallingAdvisor 把 message 回传模型继续循环（模型会转述给用户并调整策略）</li>
 *   <li>IO 异常 → 统一 catch 包装为 {@link IllegalStateException}；
 *       <b>严禁裸抛 {@link IOException}</b>（非 RuntimeException 会被异常处理器直接重抛、中断整次会话）</li>
 * </ul>
 */
public final class FileTools {

    private static final DateTimeFormatter M_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FileSandboxPolicy sandboxPolicy;
    private final long maxReadSizeBytes;

    public FileTools(FileToolProperties props, FileSandboxPolicy sandboxPolicy) {
        this.sandboxPolicy = sandboxPolicy;
        this.maxReadSizeBytes = props.maxReadSizeBytes();
    }

    @Tool(name = "read_file", description = "读取工作区或用户授权目录内文本文件的内容并返回完整文本。"
            + "适用于 .md / .txt / .csv / .json 等 UTF-8 纯文本文件；不适用于图片、Word、Excel 等二进制文件。"
            + "文件较大或不确定路径时，先调用 list_files 查看目录结构。")
    public String readFile(@ToolParam(description = "文件路径。相对路径以当前工作区根为基准；"
            + "也支持绝对路径，未授权目录会触发用户确认", required = true) String path) {
        Path target = sandboxPolicy.resolveForRead(path);
        try {
            if (!Files.exists(target)) {
                throw new IllegalArgumentException("操作失败：文件不存在。请先调用 list_files 确认路径。");
            }
            if (Files.isDirectory(target)) {
                throw new IllegalArgumentException("操作失败：目标是一个目录，不是文件。请传入具体文件路径。");
            }
            long size = Files.size(target);
            if (size > maxReadSizeBytes) {
                throw new IllegalArgumentException("操作失败：文件大小（" + size + " 字节）超过读取上限（"
                        + maxReadSizeBytes + " 字节）。请告知用户：可精简文件或分段处理。");
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：读取文件失败。" + e.getMessage(), e);
        }
    }

    @Tool(name = "write_file", description = "将文本内容写入工作区或用户授权目录内的文件。文件已存在则整体覆盖；"
            + "父目录不存在会自动创建。用于保存生成的分析结果、报告草稿等产物。"
            + "写入前若不确定目标是否已存在，先调用 list_files 确认，避免误覆盖。")
    public String writeFile(
            @ToolParam(description = "目标文件路径，相对路径以当前工作区根为基准；也支持绝对路径，未授权目录会触发用户确认", required = true) String path,
            @ToolParam(description = "要写入的完整文件内容", required = true) String content) {
        Path target = sandboxPolicy.resolveForWrite(path);
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes);
            return "已写入 " + target + "（" + bytes.length + " 字节）";
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：写入文件失败。" + e.getMessage(), e);
        }
    }

    @Tool(name = "list_files", description = "列出工作区或用户授权目录内某目录下的文件和子目录（名称、类型、大小、修改时间）。"
            + "用于查看有哪些可用数据文件、确认目标是否已存在、规划下一步操作。"
            + "只列一层，不递归子目录；想看子目录内容需传入子目录路径。")
    public String listFiles(@ToolParam(description = "目录路径；传入 \".\" 表示当前工作区根；相对路径以工作区根为基准",
            required = false) String path) {
        String dirPath = (path == null || path.isBlank()) ? "." : path;
        Path dir = sandboxPolicy.resolveForRead(dirPath);
        if (!Files.exists(dir)) {
            throw new IllegalArgumentException("操作失败：目录不存在。请先调用 list_files 确认路径。");
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("操作失败：目标不是目录。请传入目录路径。");
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> entries = Files.list(dir)) {
            java.util.List<Path> sorted = entries.sorted(
                    Comparator.comparingInt((Path p) -> Files.isDirectory(p) ? 0 : 1)
                            .thenComparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .toList();
            for (Path entry : sorted) {
                boolean isDir = Files.isDirectory(entry);
                String name = entry.getFileName().toString();
                String size = "";
                String mtime = "";
                if (!isDir) {
                    try {
                        size = formatSize(Files.size(entry));
                        mtime = M_TIME.format(LocalDateTime.ofInstant(
                                Files.getLastModifiedTime(entry).toInstant(), java.time.ZoneId.systemDefault()));
                    } catch (IOException ignored) {
                        // 单个条目信息读取失败不阻断整体列出
                    }
                }
                sb.append(isDir ? "[目录] " : "[文件] ").append(name)
                        .append(isDir ? "" : "  " + size + "  " + mtime)
                        .append('\n');
            }
        } catch (IOException e) {
            throw new IllegalStateException("操作失败：列出目录失败。" + e.getMessage(), e);
        }
        return sb.isEmpty() ? "（空目录）" : sb.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
