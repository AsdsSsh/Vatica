package com.example.vatica.config;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

/**
 * Windows 开发模式环境变量回填（迭代 10 后热修复）：{@code setx} 只写注册表、
 * 已启动的 shell/IDE 继承的是旧环境快照——后端启动时若某变量缺失/为空，
 * 从 {@code HKCU\Environment} 读注册表值写入 JVM system properties，
 * 供 {@code application.yml} 的 {@code ${VAR:...}} 占位符解析。
 *
 * <p>与打包版 Rust 启动器的 {@code merge_registry_user_env} 同一策略：
 * 注册表只在"当前进程环境缺失"时兜底，命令行显式传入的值优先级更高。
 * 只在 Windows 生效；其他平台直接 no-op。
 */
public final class WindowsRegistryEnvBackfill {

    private static final String REG_KEY = "HKCU\\Environment";
    private static final String REG_SZ = "REG_SZ";

    /** 后端配置引用的全部环境变量键（与 launcher/main.rs 的白名单保持一致）。迭代 13：模型 Key 已去环境变量。 */
    private static final List<String> KEYS = List.of(
            "AMAP_MCP_KEY",
            "MAIL_IMAP_HOST",
            "MAIL_IMAP_PORT",
            "MAIL_SMTP_HOST",
            "MAIL_SMTP_PORT",
            "MAIL_USERNAME",
            "MAIL_PASSWORD",
            "MYSQL_HOST",
            "MYSQL_PORT",
            "MYSQL_DATABASE",
            "MYSQL_USERNAME",
            "MYSQL_PASSWORD",
            "PACKAGED_DB_URL",
            "PACKAGED_DB_USERNAME",
            "PACKAGED_DB_PASSWORD");

    private WindowsRegistryEnvBackfill() {
    }

    /** 启动早期调用：只补当前进程环境中缺失的键，不覆盖显式传入值。 */
    public static void backfill() {
        if (!isWindows()) {
            return;
        }
        for (String key : KEYS) {
            String current = System.getenv(key);
            if (current != null && !current.isBlank()) {
                continue;
            }
            String value = query(key);
            if (value != null && !value.isBlank()) {
                System.setProperty(key, value);
                System.out.println("[vatica] 环境变量 " + key + " 未在当前进程生效，"
                        + "已从 HKCU\\Environment 回填（setx 后无需注销重登）");
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 执行 {@code reg query HKCU\Environment /v <name>} 并解析 REG_SZ 值。 */
    static String query(String name) {
        ProcessBuilder builder = new ProcessBuilder("reg", "query", REG_KEY, "/v", name);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
            int exit = process.waitFor();
            if (exit != 0) {
                return null;
            }
            return parseValue(output, name);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 解析 reg query 输出中的 REG_SZ 值（值内部空格保留，首尾空白去掉）。 */
    static String parseValue(String output, String name) {
        if (output == null) {
            return null;
        }
        String upperName = name.toUpperCase(Locale.ROOT);
        for (String line : output.lines().toList()) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (!upper.contains(upperName) || !upper.contains(REG_SZ)) {
                continue;
            }
            int marker = upper.indexOf(REG_SZ);
            String value = line.substring(marker + REG_SZ.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }
}
