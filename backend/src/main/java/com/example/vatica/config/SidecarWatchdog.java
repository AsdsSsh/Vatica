package com.example.vatica.config;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * sidecar 看门狗（迭代 8 I8-1，仅打包模式生效）：桌面壳经启动器进程拉起本后端，
 * 启动器把自身 PID 注入环境变量 {@code VATICA_WATCHDOG_PID}。
 * 启动器消失（壳正常退出时被 kill / 启动器单独被强杀）时，本看门狗轮询感知并自行退出，
 * 防止 8080 端口留下孤儿后端进程。
 *
 * <p>两层收尾设计（与启动器的父进程看门狗互为兜底）：
 * <ul>
 *   <li>壳正常退出 → 壳 kill 启动器 → 本看门狗（10 秒内）感知 → {@code System.exit(0)} 优雅收尾（走 shutdown hook，H2 落盘）；</li>
 *   <li>壳被强杀/崩溃 → 启动器的父进程看门狗（2 秒轮询）自行退出 → 本看门狗感知启动器消失后优雅收尾——Windows 强杀不级联子进程，两层接力覆盖此路径；</li>
 *   <li>启动器单独被强杀 → 本看门狗兜底。</li>
 * </ul>
 *
 * <p>开发模式（无该环境变量）完全不生效；正常退出路径由启动器 wait + 壳侧 kill 负责。
 */
@Component
public class SidecarWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SidecarWatchdog.class);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        String raw = System.getenv("VATICA_WATCHDOG_PID");
        if (raw == null || raw.isBlank()) {
            return;
        }
        final long launcherPid;
        try {
            launcherPid = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("VATICA_WATCHDOG_PID 非法（{}），看门狗不启用", raw);
            return;
        }
        log.info("sidecar 看门狗启动：监控启动器 pid={}", launcherPid);
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vatica-sidecar-watchdog");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(() -> {
            boolean alive = ProcessHandle.of(launcherPid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                log.info("sidecar 启动器（pid={}）已退出，后端自动收尾", launcherPid);
                System.exit(0);
            }
        }, 10, 10, TimeUnit.SECONDS);
    }
}
