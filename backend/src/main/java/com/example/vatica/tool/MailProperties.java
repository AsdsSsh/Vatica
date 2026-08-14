package com.example.vatica.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮箱配置（{@code vatica.mail.*}，迭代 3.5）。
 *
 * <p>配置走环境变量（仿 DEEPSEEK_API_KEY 模式）：不硬编码账号密码，
 * 未配置时邮件工具返回"未配置"指引（不崩溃）。默认端口：IMAP 993（SSL）、SMTP 465（SSL）。
 *
 * @param imapHost IMAP 服务器地址（如 imap.qq.com）
 * @param imapPort IMAP 端口，默认 993
 * @param smtpHost SMTP 服务器地址（如 smtp.qq.com）
 * @param smtpPort SMTP 端口，默认 465
 * @param username 登录用户名（通常即邮箱地址）
 * @param password 登录密码/授权码
 */
@ConfigurationProperties(prefix = "vatica.mail")
public record MailProperties(String imapHost, int imapPort, String smtpHost, int smtpPort,
        String username, String password) {

    public static final int DEFAULT_IMAP_PORT = 993;
    public static final int DEFAULT_SMTP_PORT = 465;

    public MailProperties {
        if (imapHost == null) {
            imapHost = "";
        }
        if (smtpHost == null) {
            smtpHost = "";
        }
        if (username == null) {
            username = "";
        }
        if (password == null) {
            password = "";
        }
        if (imapPort <= 0) {
            imapPort = DEFAULT_IMAP_PORT;
        }
        if (smtpPort <= 0) {
            smtpPort = DEFAULT_SMTP_PORT;
        }
    }

    /** 三要素齐全才算已配置（用户名 + IMAP + SMTP）。 */
    public boolean configured() {
        return !username.isBlank() && !imapHost.isBlank() && !smtpHost.isBlank();
    }
}
