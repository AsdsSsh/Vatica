package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** 迭代 13 I13-4：用户自配槽位 + EPHEMERAL/ENCRYPTED_AT_REST 开关。 */
@DirtiesContext
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-user-model;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class UserModelServiceTest {

    @Autowired
    UserModelService service;
    @Autowired
    UserModelCredentialRepository credentials;

    private UserModelService.SaveRequest request(String mode, String key) {
        return new UserModelService.SaveRequest("我的 DeepSeek", ModelSlot.PROTOCOL_OPENAI,
                "https://api.deepseek.com", "deepseek-v4-flash", 0.7, true, mode, key);
    }

    @Test
    void ephemeralSlotNeverStoresKey() {
        UserModelService.View created = service.create(7L, request(UserModelSlot.MODE_EPHEMERAL, "sk-temp"));

        assertThat(created.credentialMode()).isEqualTo(UserModelSlot.MODE_EPHEMERAL);
        assertThat(created.apiKeySet()).isFalse();
        assertThat(credentials.existsById(created.id())).isFalse();
        assertThat(service.resolveApiKey(7L, created.id())).isEmpty();
    }

    @Test
    void encryptedSlotStoresAndResolvesKey() {
        UserModelService.View created = service.create(7L,
                request(UserModelSlot.MODE_ENCRYPTED_AT_REST, "sk-live-12345678"));

        assertThat(created.apiKeySet()).isTrue();
        assertThat(created.apiKeyHint()).endsWith("5678");
        assertThat(service.resolveApiKey(7L, created.id())).isEqualTo("sk-live-12345678");
    }

    @Test
    void switchingModeClearsOrRequiresKey() {
        UserModelService.View slot = service.create(7L,
                request(UserModelSlot.MODE_ENCRYPTED_AT_REST, "sk-live-12345678"));

        UserModelService.View ephemeral = service.setMode(7L, slot.id(), UserModelSlot.MODE_EPHEMERAL, null);
        assertThat(credentials.existsById(slot.id())).isFalse();
        assertThat(ephemeral.apiKeySet()).isFalse();

        assertThatThrownBy(() -> service.setMode(7L, slot.id(), UserModelSlot.MODE_ENCRYPTED_AT_REST, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供 API Key");
    }

    @Test
    void otherOwnerCannotAccessSlot() {
        UserModelService.View slot = service.create(7L,
                request(UserModelSlot.MODE_ENCRYPTED_AT_REST, "sk-live-12345678"));

        assertThatThrownBy(() -> service.resolveSlot(8L, slot.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无权访问");
    }

    /** 迭代 13.5：编辑时把模式切回 EPHEMERAL，即使 apiKey 留空也必须删除云端密文。 */
    @Test
    void updateToEphemeralAlwaysClearsCloudCredential() {
        UserModelService.View slot = service.create(7L,
                request(UserModelSlot.MODE_ENCRYPTED_AT_REST, "sk-live-12345678"));

        UserModelService.View updated = service.update(7L, slot.id(),
                request(UserModelSlot.MODE_EPHEMERAL, null));

        assertThat(updated.credentialMode()).isEqualTo(UserModelSlot.MODE_EPHEMERAL);
        assertThat(credentials.existsById(slot.id())).isFalse();
    }

    /** 迭代 13.5：编辑时切到 ENCRYPTED_AT_REST 但既没填新 key 也没有旧密文 → 快速失败。 */
    @Test
    void updateToEncryptedWithoutAnyKeyFails() {
        UserModelService.View slot = service.create(7L,
                request(UserModelSlot.MODE_EPHEMERAL, "sk-temp"));

        assertThatThrownBy(() -> service.update(7L, slot.id(),
                request(UserModelSlot.MODE_ENCRYPTED_AT_REST, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须提供 API Key");
    }

    /** 迭代 13.5：ENCRYPTED_AT_REST 槽位只改名字（apiKey null）不应把旧密文丢掉。 */
    @Test
    void updateEncryptedWithNullKeyKeepsExistingCredential() {
        UserModelService.View slot = service.create(7L,
                request(UserModelSlot.MODE_ENCRYPTED_AT_REST, "sk-live-12345678"));

        UserModelService.View updated = service.update(7L, slot.id(),
                request(UserModelSlot.MODE_ENCRYPTED_AT_REST, null));

        assertThat(updated.apiKeySet()).isTrue();
        assertThat(service.resolveApiKey(7L, slot.id())).isEqualTo("sk-live-12345678");
    }
}
