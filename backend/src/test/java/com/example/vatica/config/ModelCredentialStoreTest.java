package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** 迭代 13 I13-3：模型凭据密文存取（set/read/clear/keyVersion 递增）。 */
@DirtiesContext
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-cred;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class ModelCredentialStoreTest {

    @Autowired
    ModelCredentialStore store;
    @Autowired
    ModelCredentialRepository repository;

    @Test
    void storesEncryptedAndResolvesPlaintext() {
        store.put("ds", "sk-live-1234567890abcd");

        assertThat(repository.findById("ds")).isPresent();
        assertThat(repository.findById("ds").orElseThrow().getCiphertext()).doesNotContain("sk-live");
        assertThat(store.resolve("ds").orElseThrow().apiKey()).isEqualTo("sk-live-1234567890abcd");
        assertThat(store.resolve("ds").orElseThrow().hint()).isEqualTo("…abcd");
    }

    @Test
    void overwriteIncrementsVersionAndClearRemovesRow() {
        store.put("ds", "first");
        store.put("ds", "second");

        assertThat(store.resolve("ds").orElseThrow().keyVersion()).isEqualTo(2);
        assertThat(store.resolve("ds").orElseThrow().apiKey()).isEqualTo("second");

        store.clear("ds");
        assertThat(repository.existsById("ds")).isFalse();
    }

    /** 迭代 13.5：槽位列表保存后，被删除槽位的密钥密文一并清理。 */
    @Test
    void clearAllExceptRemovesOrphanCredentials() {
        store.put("keep", "sk-keep");
        store.put("deleted", "sk-orphan");

        store.clearAllExcept(java.util.List.of("keep"));

        assertThat(repository.existsById("keep")).isTrue();
        assertThat(repository.existsById("deleted")).isFalse();
    }
}
