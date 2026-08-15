package com.example.vatica.permission;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件权限运行时请求（迭代 11）：后端只负责"发事件 + 等待决定 + 超时按拒绝"，
 * 不持久化任何授权——永久授权由前端 localStorage 持有。
 */
@Service
public class FilePermissionRequestService {

    private static final Logger log = LoggerFactory.getLogger(FilePermissionRequestService.class);

    /** 用户无操作时的自动拒绝时间。 */
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    private static final class Pending {
        final FilePermissionRequest request;
        final CompletableFuture<Boolean> future;

        Pending(FilePermissionRequest request) {
            this.request = request;
            this.future = new CompletableFuture<>();
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final PermissionEventPublisher publisher;

    public FilePermissionRequestService(PermissionEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** 请求用户授权；channel 为空（MCP/无 UI）时立即按拒绝返回。 */
    public void request(Path path, FileAccess access, FilePermissionPolicy policy, String channel,
            String description) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException(
                    "操作失败：路径不在已授权目录（" + path + "）。当前会话没有可交互的权限弹窗，请先在文件权限设置中授权该目录。");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        FilePermissionRequest request = new FilePermissionRequest(id, channel, path.toString(),
                access, policy.mode(), description, Instant.now());
        Pending item = new Pending(request);
        pending.put(id, item);
        boolean delivered = publisher.publish(request);
        if (!delivered) {
            pending.remove(id);
            throw new IllegalArgumentException(
                    "操作失败：路径不在已授权目录（" + path + "）。当前没有可接收权限弹窗的订阅者，请先在文件权限设置中授权该目录。");
        }

        boolean approved;
        try {
            approved = item.future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            approved = false;
            log.info("权限请求 {} 超时/中断，按拒绝处理：{}", id, request.path());
        } finally {
            pending.remove(id);
        }
        if (!approved) {
            throw new IllegalArgumentException(
                    "操作失败：用户拒绝了文件权限请求（" + path + "，操作=" + access + "）。请换用已授权目录或让用户重新授权。");
        }
    }

    /** 前端批准/拒绝入口。remember 仅作为回执语义——永久授权由前端存储。 */
    public boolean decide(String requestId, boolean approved, boolean remember) {
        Pending item = pending.get(requestId);
        if (item == null) {
            throw new IllegalArgumentException("操作失败：权限请求不存在或已超时（" + requestId + "）。");
        }
        item.future.complete(approved);
        log.info("权限请求 {} 已被{}（remember={}）：{}", requestId, approved ? "批准" : "拒绝",
                remember, item.request.path());
        return approved;
    }

    /** 任务取消 / 聊天停止：该通道所有挂起请求按拒绝处理。 */
    public void cancelChannel(String channel) {
        if (channel == null) {
            return;
        }
        pending.values().stream()
                .filter(p -> channel.equals(p.request.channel()))
                .forEach(p -> p.future.complete(false));
    }
}
