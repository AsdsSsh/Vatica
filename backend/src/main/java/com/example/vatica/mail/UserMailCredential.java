package com.example.vatica.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_mail_credential")
public class UserMailCredential {
    @Id private Long userId;
    @Column(nullable = false, length = 32) private String hint;
    @Column(nullable = false, columnDefinition = "TEXT") private String wrappedDek;
    @Column(nullable = false, length = 32) private String dekNonce;
    @Column(nullable = false, columnDefinition = "TEXT") private String ciphertext;
    @Column(nullable = false, length = 32) private String dataNonce;
    @Column(nullable = false) private int keyVersion;
    protected UserMailCredential() { }
    public UserMailCredential(Long userId, String hint, String wrappedDek, String dekNonce,
            String ciphertext, String dataNonce, int keyVersion) {
        this.userId = userId; this.hint = hint; this.wrappedDek = wrappedDek; this.dekNonce = dekNonce;
        this.ciphertext = ciphertext; this.dataNonce = dataNonce; this.keyVersion = keyVersion;
    }
    public String getHint() { return hint; }
    public String getWrappedDek() { return wrappedDek; }
    public String getDekNonce() { return dekNonce; }
    public String getCiphertext() { return ciphertext; }
    public String getDataNonce() { return dataNonce; }
}
