package com.example.vatica.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_mail_settings")
public class UserMailSettings {
    @Id private Long userId;
    @Column(nullable = false, updatable = false) private Long orgId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private MailCredentialMode credentialMode;
    @Column(nullable = false, length = 255) private String imapHost;
    @Column(nullable = false) private int imapPort;
    @Column(nullable = false, length = 255) private String smtpHost;
    @Column(nullable = false) private int smtpPort;
    @Column(nullable = false, length = 255) private String username;

    protected UserMailSettings() { }
    public UserMailSettings(Long userId, Long orgId, MailCredentialMode credentialMode, String imapHost,
            int imapPort, String smtpHost, int smtpPort, String username) {
        this.userId = userId;
        this.orgId = orgId;
        update(credentialMode, imapHost, imapPort, smtpHost, smtpPort, username);
    }
    public void update(MailCredentialMode mode, String imapHost, int imapPort, String smtpHost,
            int smtpPort, String username) {
        this.credentialMode = mode;
        this.imapHost = imapHost == null ? "" : imapHost.trim();
        this.imapPort = imapPort <= 0 ? 993 : imapPort;
        this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
        this.smtpPort = smtpPort <= 0 ? 465 : smtpPort;
        this.username = username == null ? "" : username.trim();
    }
    public Long getUserId() { return userId; }
    public MailCredentialMode getCredentialMode() { return credentialMode; }
    public String getImapHost() { return imapHost; }
    public int getImapPort() { return imapPort; }
    public String getSmtpHost() { return smtpHost; }
    public int getSmtpPort() { return smtpPort; }
    public String getUsername() { return username; }
}
