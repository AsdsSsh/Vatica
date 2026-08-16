package com.example.vatica.config;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 模型凭据密文（迭代 13 I13-3）：与模型元数据分表，只存信封加密三件套。
 * 明文 API Key 永不落库；hint 仅保留"末 4 位"用于界面显示。
 */
@Entity
@Table(name = "model_credential")
public class ModelCredential {

    @Id
    @Column(length = 64)
    private String slotId;

    @Column(nullable = false, length = 32)
    private String hint;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String wrappedDek;

    @Column(nullable = false, length = 32)
    private String dekNonce;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String ciphertext;

    @Column(nullable = false, length = 32)
    private String dataNonce;

    @Column(nullable = false)
    private int keyVersion;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }

    protected ModelCredential() {
    }

    public ModelCredential(String slotId, String hint, String wrappedDek, String dekNonce,
            String ciphertext, String dataNonce, int keyVersion) {
        this.slotId = slotId;
        this.hint = hint;
        this.wrappedDek = wrappedDek;
        this.dekNonce = dekNonce;
        this.ciphertext = ciphertext;
        this.dataNonce = dataNonce;
        this.keyVersion = keyVersion;
    }

    public String getSlotId() {
        return slotId;
    }

    public String getHint() {
        return hint;
    }

    public String getWrappedDek() {
        return wrappedDek;
    }

    public String getDekNonce() {
        return dekNonce;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public String getDataNonce() {
        return dataNonce;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
