package com.example.vatica.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import com.example.vatica.config.AppStateProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 迭代 13 I13-1：信封加密往返 / 篡改检测 / 主密钥文件持久化。 */
class SecretCryptoTest {

    @TempDir
    Path dir;

    @Test
    void roundTripDecryptsOriginal() {
        SecretCrypto crypto = crypto();

        SecretCrypto.EncryptedSecret encrypted = crypto.encrypt("sk-test-123");

        assertThat(encrypted.ciphertext()).isNotEqualTo("sk-test-123");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("sk-test-123");
    }

    @Test
    void eachEncryptionUsesFreshDekAndNonce() {
        SecretCrypto crypto = crypto();

        SecretCrypto.EncryptedSecret a = crypto.encrypt("same");
        SecretCrypto.EncryptedSecret b = crypto.encrypt("same");

        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
        assertThat(a.wrappedDek()).isNotEqualTo(b.wrappedDek());
    }

    @Test
    void tamperedCiphertextFailsToDecrypt() {
        SecretCrypto crypto = crypto();
        SecretCrypto.EncryptedSecret encrypted = crypto.encrypt("secret");

        byte[] tampered = Base64.getDecoder().decode(encrypted.ciphertext());
        tampered[0] ^= 1;
        SecretCrypto.EncryptedSecret bad = new SecretCrypto.EncryptedSecret(encrypted.wrappedDek(),
                encrypted.dekNonce(), Base64.getEncoder().encodeToString(tampered), encrypted.dataNonce());

        assertThatThrownBy(() -> crypto.decrypt(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void masterKeyFileIsPersistedAcrossRestart() throws Exception {
        AppStateProperties props = new AppStateProperties(dir.toString());
        FileMasterKeyProvider first = new FileMasterKeyProvider(props);
        FileMasterKeyProvider second = new FileMasterKeyProvider(props);

        assertThat(second.rawKey()).isEqualTo(first.rawKey());
        assertThat(Files.exists(dir.resolve("master.key"))).isTrue();
    }

    @Test
    void fingerprintIsStableAndShort() {
        assertThat(SecretCrypto.fingerprint("same")).isEqualTo(SecretCrypto.fingerprint("same"));
        assertThat(SecretCrypto.fingerprint("a")).hasSize(24);
    }

    private SecretCrypto crypto() {
        return new SecretCrypto(new FileMasterKeyProvider(new AppStateProperties(dir.toString())));
    }
}
