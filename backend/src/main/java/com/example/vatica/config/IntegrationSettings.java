package com.example.vatica.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.vatica.secret.MasterKeyProvider;
import com.example.vatica.secret.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 外部服务集成配置（迭代 13 I13-9）：AMAP / 邮件 / 数据库。
 * 整个配置 JSON 信封加密后落 `.vatica/integrations.json`；服务与启动后处理器共用。
 */
public record IntegrationSettings(Amap amap, Mail mail, Db db) {

    public static final String MODE_H2 = "H2";
    public static final String MODE_MYSQL = "MYSQL";

    public record Amap(String apiKey) {
    }

    public record Mail(String imapHost, int imapPort, String smtpHost, int smtpPort,
            String username, String password) {
    }

    public record Db(String mode, String host, int port, String database,
            String username, String password) {
    }

    public static IntegrationSettings defaults() {
        return new IntegrationSettings(new Amap(""),
                new Mail("", 993, "", 465, "", ""),
                new Db(MODE_MYSQL, "localhost", 3306, "vatica", "vatica", ""));
    }

    public static IntegrationSettings load(Path stateDir, MasterKeyProvider masterKey, ObjectMapper mapper) {
        Path file = stateDir.resolve("integrations.json");
        if (!Files.exists(file)) {
            return null;   // 未配置过：调用方回退默认/yml，不覆盖启动配置
        }
        try {
            SecretCrypto crypto = new SecretCrypto(masterKey);
            Stored stored = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
            String json = crypto.decrypt(new SecretCrypto.EncryptedSecret(
                    stored.wrappedDek(), stored.dekNonce(), stored.ciphertext(), stored.dataNonce()));
            return mapper.readValue(json, IntegrationSettings.class);
        } catch (Exception e) {
            return defaults();
        }
    }

    public static void save(Path stateDir, MasterKeyProvider masterKey, ObjectMapper mapper,
            IntegrationSettings settings) {
        try {
            SecretCrypto crypto = new SecretCrypto(masterKey);
            String json = mapper.writeValueAsString(settings);
            SecretCrypto.EncryptedSecret secret = crypto.encrypt(json);
            Stored stored = new Stored(1, secret.wrappedDek(), secret.dekNonce(), secret.ciphertext(),
                    secret.dataNonce());
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve("integrations.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stored), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("操作失败：无法保存外部服务配置。" + e.getMessage(), e);
        }
    }

    /** 密文文件外层结构。 */
    public record Stored(int version, String wrappedDek, String dekNonce, String ciphertext, String dataNonce) {
    }
}
