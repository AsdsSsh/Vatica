package com.example.vatica.config;

import java.util.Collection;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.secret.SecretCrypto;

/**
 * 模型凭据密文存取（迭代 13 I13-3）：
 * {@code put} 用信封加密落库（key_version+1，旧密文覆盖）；{@code resolve} 解密返回明文
 * （只存在于内存调用链）；{@code clear} 删除密文行；{@code clearAllExcept} 清理
 * 已不在配置列表中的孤儿凭据（迭代 13.5）。hint = 末 4 位。
 */
@Service
public class ModelCredentialStore {

    private final ModelCredentialRepository repository;
    private final SecretCrypto crypto;

    public ModelCredentialStore(ModelCredentialRepository repository, SecretCrypto crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @Transactional
    public void put(String slotId, String apiKey) {
        if (slotId == null || slotId.isBlank()) {
            throw new IllegalArgumentException("操作失败：凭据槽位 id 不能为空。");
        }
        if (apiKey == null || apiKey.isBlank()) {
            clear(slotId);
            return;
        }
        SecretCrypto.EncryptedSecret secret = crypto.encrypt(apiKey);
        ModelCredential existing = repository.findById(slotId).orElse(null);
        int nextVersion = existing == null ? 1 : existing.getKeyVersion() + 1;
        repository.save(new ModelCredential(slotId, makeHint(apiKey), secret.wrappedDek(), secret.dekNonce(),
                secret.ciphertext(), secret.dataNonce(), nextVersion));
    }

    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String slotId) {
        return repository.findById(slotId)
                .map(row -> new Resolved(
                        crypto.decrypt(new SecretCrypto.EncryptedSecret(row.getWrappedDek(), row.getDekNonce(),
                                row.getCiphertext(), row.getDataNonce())),
                        row.getHint(), row.getKeyVersion()));
    }

    @Transactional(readOnly = true)
    public Optional<String> hint(String slotId) {
        return repository.findById(slotId).map(ModelCredential::getHint);
    }

    @Transactional
    public void clear(String slotId) {
        repository.deleteById(slotId);
    }

    /** 迭代 13.5：删除所有不在 {@code keptSlotIds} 中的凭据（槽位删除后不再留密钥密文）。 */
    @Transactional
    public void clearAllExcept(Collection<String> keptSlotIds) {
        if (keptSlotIds == null || keptSlotIds.isEmpty()) {
            repository.deleteAllInBatch();
            return;
        }
        repository.deleteAll(repository.findAll().stream()
                .filter(row -> !keptSlotIds.contains(row.getSlotId()))
                .toList());
    }

    public record Resolved(String apiKey, String hint, int keyVersion) {
    }

    private static String makeHint(String apiKey) {
        String visible = apiKey.length() <= 4 ? apiKey : apiKey.substring(apiKey.length() - 4);
        return "…" + visible;
    }
}
