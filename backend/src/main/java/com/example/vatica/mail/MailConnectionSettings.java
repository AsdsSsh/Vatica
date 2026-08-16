package com.example.vatica.mail;

import com.example.vatica.tool.MailProperties;

/** 请求级或解密后的邮件连接参数；password 永不出现在响应 DTO。 */
public record MailConnectionSettings(String imapHost, int imapPort, String smtpHost, int smtpPort,
        String username, String password) {
    public MailProperties toProperties() {
        return new MailProperties(imapHost, imapPort, smtpHost, smtpPort, username, password);
    }
}
