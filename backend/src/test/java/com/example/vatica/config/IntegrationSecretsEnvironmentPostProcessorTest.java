package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import com.example.vatica.secret.FileMasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 13 I13-9：启动后处理器从密文集成配置注入 Environment。 */
class IntegrationSecretsEnvironmentPostProcessorTest {

    @TempDir
    Path dir;

    @Test
    void injectsDecryptedMailAmapAndH2Properties() {
        AppStateProperties props = new AppStateProperties(dir.toString());
        FileMasterKeyProvider masterKey = new FileMasterKeyProvider(props);
        ObjectMapper mapper = new ObjectMapper();
        IntegrationSettings settings = new IntegrationSettings(
                new IntegrationSettings.Amap("amap-key-123"),
                new IntegrationSettings.Mail("imap.qq.com", 993, "smtp.qq.com", 465,
                        "me@qq.com", "mail-pass"),
                new IntegrationSettings.Db(IntegrationSettings.MODE_H2, "localhost", 3306,
                        "vatica", "vatica", ""));
        IntegrationSettings.save(dir, masterKey, mapper, settings);

        MockEnvironment env = new MockEnvironment().withProperty("vatica.app.state-dir", dir.toString());
        new IntegrationSecretsEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.ai.mcp.client.streamable-http.connections.amap.endpoint"))
                .isEqualTo("/mcp?key=amap-key-123");
        assertThat(env.getProperty("vatica.mail.username")).isEqualTo("me@qq.com");
        assertThat(env.getProperty("vatica.mail.password")).isEqualTo("mail-pass");
        assertThat(env.getProperty("spring.datasource.url")).contains("jdbc:h2:file:");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("sa");
    }

    @Test
    void injectsPostgresqlPropertiesByDefaultMode() {
        AppStateProperties props = new AppStateProperties(dir.toString());
        FileMasterKeyProvider masterKey = new FileMasterKeyProvider(props);
        ObjectMapper mapper = new ObjectMapper();
        IntegrationSettings.save(dir, masterKey, mapper, new IntegrationSettings(
                new IntegrationSettings.Amap(""),
                new IntegrationSettings.Mail("", 993, "", 465, "", ""),
                new IntegrationSettings.Db(IntegrationSettings.MODE_POSTGRESQL, "db.example", 5432,
                        "vatica", "vatica", "secret")));

        MockEnvironment env = new MockEnvironment().withProperty("vatica.app.state-dir", dir.toString());
        new IntegrationSecretsEnvironmentPostProcessor().postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://db.example:5432/vatica");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("vatica");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("secret");
    }
}
