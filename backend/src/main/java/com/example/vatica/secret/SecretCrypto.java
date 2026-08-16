package com.example.vatica.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 信封加密（迭代 13 I13-1）：每条秘密用独立随机 DEK 加密（AES-256-GCM），
 * DEK 再用主密钥包裹（AES-256-GCM）。换主密钥只需重包 wrappedDek；
 * 未来换云 KMS 只替换 DEK 包裹实现。
 */
public final class SecretCrypto {

    private static final int DEK_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** 密文三件套（Base64），可直接 JSON 落库。 */
    public record EncryptedSecret(String wrappedDek, String dekNonce, String ciphertext, String dataNonce) {
    }

    private final MasterKeyProvider masterKey;

    public SecretCrypto(MasterKeyProvider masterKey) {
        this.masterKey = masterKey;
    }

    public EncryptedSecret encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            SecureRandom random = new SecureRandom();
            byte[] dek = new byte[DEK_BYTES];
            random.nextBytes(dek);
            byte[] dataNonce = new byte[GCM_NONCE_BYTES];
            random.nextBytes(dataNonce);
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, dek, dataNonce,
                    plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] dekNonce = new byte[GCM_NONCE_BYTES];
            random.nextBytes(dekNonce);
            byte[] wrappedDek = crypt(Cipher.ENCRYPT_MODE, masterKey.rawKey(), dekNonce, dek);
            return new EncryptedSecret(b64(wrappedDek), b64(dekNonce), b64(ciphertext), b64(dataNonce));
        } catch (Exception e) {
            throw new IllegalStateException("信封加密失败：" + e.getMessage(), e);
        }
    }

    public String decrypt(EncryptedSecret secret) {
        if (secret == null) {
            return null;
        }
        try {
            byte[] dek = crypt(Cipher.DECRYPT_MODE, masterKey.rawKey(), decode(secret.dekNonce()),
                    decode(secret.wrappedDek()));
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, dek, decode(secret.dataNonce()),
                    decode(secret.ciphertext()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("密文解密失败（主密钥或密文不匹配）：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 秘密的短指纹（SHA-256 前 12 字节 hex），用于缓存键，绝不参与解密。 */
    public static String fingerprint(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(input);
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
