package com.example.vatica.permission;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件权限运行时请求（迭代 11）：后端只负责"发事件 + 等待决定 + 超时按拒绝"。
 * 迭代 12 I12-7：同一 channel 的 approve(remember) 增加<b>内存级临时授权</b>——
 * 后续工具调用先查临时授权，命中直接放行，不再二次弹窗；channel 收尾/取消时清理。
 * 永久授权仍由前端 localStorage 持有，后端不落盘。
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

    /** channel → 本次会话/任务内已批准并选择"记住"的路径授权（内存级，不落盘）。 */
    private record TempGrant(String pathKey, FileAccess access) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Set<TempGrant>> grants = new ConcurrentHashMap<>();
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

    /** 前端批准/拒绝入口。remember=true 时把本次授权记入当前 channel 内存级临时授权。 */
    public boolean decide(String requestId, boolean approved, boolean remember) {
        Pending item = pending.get(requestId);
        if (item == null) {
            throw new IllegalArgumentException("操作失败：权限请求不存在或已超时（" + requestId + "）。");
        }
        item.future.complete(approved);
        if (approved && remember) {
            rememberGrant(item.request.channel(), item.request.path(), item.request.access());
        }
        log.info("权限请求 {} 已被{}（remember={}）：{}", requestId, approved ? "批准" : "拒绝",
                remember, item.request.path());
        return approved;
    }

    /** 当前 channel 是否已有"记住授权"覆盖该路径与操作（路径前缀匹配，大小写归一）。 */
    public boolean isGranted(String channel, Path path, FileAccess access) {
        if (channel == null || channel.isBlank() || path == null || access == null) {
            return false;
        }
        Set<TempGrant> set = grants.get(channel);
        if (set == null || set.isEmpty()) {
            return false;
        }
        String target = pathKey(path);
        return set.stream().anyMatch(g -> g.access() == access && matches(target, g.pathKey()));
    }

    void rememberGrant(String channel, String path, FileAccess access) {
        grants.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                .add(new TempGrant(pathKey(Path.of(path)), access));
    }

    /** 任务取消 / 聊天停止：该通道所有挂起请求按拒绝处理，并清掉内存级临时授权。 */
    public void cancelChannel(String channel) {
        if (channel == null) {
            return;
        }
        grants.remove(channel);
        pending.values().stream()
                .filter(p -> channel.equals(p.request.channel()))
                .forEach(p -> p.future.complete(false));
    }

    /** 当前 channel 的临时授权数量（单测用）。 */
    int grantCount(String channel) {
        Set<TempGrant> set = grants.get(channel);
        return set == null ? 0 : set.size();
    }

    private static boolean matches(String target, String grant) {
        String sep = Path.of(".").toAbsolutePath().getFileSystem().getSeparator();
        return target.equals(grant) || target.startsWith(grant + sep);
    }

    private static String pathKey(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}
