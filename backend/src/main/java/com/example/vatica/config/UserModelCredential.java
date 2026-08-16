package com.example.vatica.config;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** 用户自配模型凭据密文（迭代 13 I13-4）：仅 ENCRYPTED_AT_REST 槽位存在。 */
@Entity
@Table(name = "user_model_credential")
public class UserModelCredential {

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

    protected UserModelCredential() {
    }

    public UserModelCredential(String slotId, String hint, String wrappedDek, String dekNonce,
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
