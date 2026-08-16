package com.example.vatica.config;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 用户自配模型槽位元数据（迭代 13 I13-4）：只存连接参数，不存 key。
 * credentialMode = EPHEMERAL（key 仅客户端/请求级）或 ENCRYPTED_AT_REST（云端密文库）。
 */
@Entity
@Table(name = "user_model_slot")
public class UserModelSlot {

    public static final String MODE_EPHEMERAL = "EPHEMERAL";
    public static final String MODE_ENCRYPTED_AT_REST = "ENCRYPTED_AT_REST";

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 16)
    private String protocol;

    @Column(nullable = false, length = 255)
    private String baseUrl;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false)
    private Double temperature;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 24)
    private String credentialMode;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }

    protected UserModelSlot() {
    }

    public UserModelSlot(String id, Long ownerId, String name, String protocol, String baseUrl,
            String model, Double temperature, boolean enabled, String credentialMode) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.enabled = enabled;
        this.credentialMode = credentialMode;
    }

    public String getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCredentialMode() {
        return credentialMode;
    }

    public void setCredentialMode(String credentialMode) {
        this.credentialMode = credentialMode;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
