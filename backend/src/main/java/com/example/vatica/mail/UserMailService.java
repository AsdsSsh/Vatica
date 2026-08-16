package com.example.vatica.mail;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.secret.SecretCrypto;
import com.example.vatica.secret.SecretCrypto.EncryptedSecret;
import com.example.vatica.tool.MailProperties;

@Service
public class UserMailService {
    private final UserMailSettingsRepository settings;
    private final UserMailCredentialRepository credentials;
    private final SecretCrypto crypto;

    public UserMailService(UserMailSettingsRepository settings, UserMailCredentialRepository credentials,
            SecretCrypto crypto) {
        this.settings = settings; this.credentials = credentials; this.crypto = crypto;
    }

    public record SaveRequest(MailCredentialMode credentialMode, String imapHost, int imapPort,
            String smtpHost, int smtpPort, String username, String password) { }
    public record View(MailCredentialMode credentialMode, String imapHost, int imapPort,
            String smtpHost, int smtpPort, String username, boolean passwordSet, String passwordHint) { }

    public View get() {
        RequestIdentity identity = RequestIdentityContext.require();
        UserMailSettings value = settings.findById(identity.userId()).orElse(null);
        if (value == null) {
            return new View(MailCredentialMode.EPHEMERAL, "", 993, "", 465, "", false, null);
        }
        UserMailCredential credential = credentials.findById(identity.userId()).orElse(null);
        return view(value, credential);
    }

    @Transactional
    public View save(SaveRequest request) {
        if (request == null || request.credentialMode() == null) {
            throw new IllegalArgumentException("操作失败：邮件凭据模式不能为空。");
        }
        RequestIdentity identity = RequestIdentityContext.require();
        UserMailSettings value = settings.findById(identity.userId())
                .orElseGet(() -> new UserMailSettings(identity.userId(), identity.orgId(), request.credentialMode(),
                        request.imapHost(), request.imapPort(), request.smtpHost(), request.smtpPort(), request.username()));
        value.update(request.credentialMode(), request.imapHost(), request.imapPort(), request.smtpHost(),
                request.smtpPort(), request.username());
        settings.save(value);
        if (request.credentialMode() == MailCredentialMode.EPHEMERAL) {
            credentials.deleteById(identity.userId());
        } else if (request.password() != null) {
            if (request.password().isBlank()) {
                credentials.deleteById(identity.userId());
            } else {
                EncryptedSecret secret = crypto.encrypt(request.password());
                String hint = "***" + request.password().substring(Math.max(0, request.password().length() - 4));
                credentials.save(new UserMailCredential(identity.userId(), hint, secret.wrappedDek(),
                        secret.dekNonce(), secret.ciphertext(), secret.dataNonce(), 1));
            }
        }
        return view(value, credentials.findById(identity.userId()).orElse(null));
    }

    public MailProperties resolve() {
        RequestIdentity identity = RequestIdentityContext.require();
        UserMailSettings value = settings.findById(identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：邮箱未配置，请先打开“我的邮箱”完成设置。"));
        if (value.getCredentialMode() == MailCredentialMode.EPHEMERAL) {
            MailConnectionSettings ephemeral = MailCredentialContext.current();
            if (ephemeral == null || ephemeral.password() == null || ephemeral.password().isBlank()) {
                throw new IllegalArgumentException("操作失败：邮箱使用仅本次模式，本次请求未携带邮箱密码。");
            }
            return ephemeral.toProperties();
        }
        UserMailCredential credential = credentials.findById(identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("操作失败：邮箱密码尚未保存。"));
        String password = crypto.decrypt(new EncryptedSecret(credential.getWrappedDek(), credential.getDekNonce(),
                credential.getCiphertext(), credential.getDataNonce()));
        return new MailProperties(value.getImapHost(), value.getImapPort(), value.getSmtpHost(),
                value.getSmtpPort(), value.getUsername(), password);
    }

    private static View view(UserMailSettings value, UserMailCredential credential) {
        return new View(value.getCredentialMode(), value.getImapHost(), value.getImapPort(), value.getSmtpHost(),
                value.getSmtpPort(), value.getUsername(), credential != null,
                credential == null ? null : credential.getHint());
    }
}
