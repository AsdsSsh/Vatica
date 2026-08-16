package com.example.vatica.secret;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import com.example.vatica.config.AppStateProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件主密钥（迭代 13 I13-1）：
 * {@code .vatica/master.key} 存 32 字节随机数（Base64），启动时读取；
 * 文件不存在则生成一次（开发/打包首启可用），生产环境应由运维预置并单独备份。
 *
 * <p>主密钥是皇冠资产：只在此类暴露原始字节，日志/API 永不输出。
 */
public final class FileMasterKeyProvider implements MasterKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(FileMasterKeyProvider.class);
    private static final String FILE_NAME = "master.key";
    private static final int KEY_BYTES = 32;

    private final byte[] key;

    public FileMasterKeyProvider(AppStateProperties appProps) {
        Path stateDir = Path.of(appProps.stateDir()).toAbsolutePath().normalize();
        Path file = stateDir.resolve(FILE_NAME);
        this.key = loadOrCreate(file);
    }

    @Override
    public byte[] rawKey() {
        return key.clone();
    }

    private static byte[] loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
                byte[] decoded = Base64.getDecoder().decode(raw);
                if (decoded.length != KEY_BYTES) {
                    throw new IllegalStateException("master.key 长度非法，拒绝启动：" + file);
                }
                return decoded;
            }
            byte[] generated = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(generated);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(generated), StandardCharsets.UTF_8);
            log.warn("已生成新的主密钥文件 {}（生产环境请立即备份并限制文件权限）", file);
            return generated;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法读取/生成主密钥文件：" + file, e);
        }
    }
}
