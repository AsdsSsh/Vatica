package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.vatica.secret.FileMasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 13.5：外部服务设置保存的合并语义（null=keep / 空串=clear / 非空=覆写）。 */
class IntegrationSettingsServiceTest {

    @TempDir
    Path dir;

    @Test
    void saveMergesSecretFieldsWithCurrentValues() {
        AppStateProperties props = new AppStateProperties(dir.toString());
        FileMasterKeyProvider masterKey = new FileMasterKeyProvider(props);
        ObjectMapper mapper = new ObjectMapper();
        IntegrationSettingsService service = new IntegrationSettingsService(props, masterKey, mapper);

        service.save(new IntegrationSettings(
                new IntegrationSettings.Amap("amap-old"),
                new IntegrationSettings.Mail("imap.qq.com", 993, "smtp.qq.com", 465,
                        "me@qq.com", "mail-old"),
                new IntegrationSettings.Db(IntegrationSettings.MODE_MYSQL, "localhost", 3306,
                        "vatica", "vatica", "db-old")));

        // 只改了数据库密码：amap key null = 保持；邮件密码空串 = 清除；其余字段照常覆写
        IntegrationSettings merged = service.save(new IntegrationSettings(
                new IntegrationSettings.Amap(null),
                new IntegrationSettings.Mail("imap.163.com", 143, "smtp.163.com", 587,
                        "me@163.com", ""),
                new IntegrationSettings.Db(IntegrationSettings.MODE_H2, "localhost", 9092,
                        "vatica2", "vatica2", "db-new")));

        assertThat(merged.amap().apiKey()).isEqualTo("amap-old");
        assertThat(merged.mail().imapHost()).isEqualTo("imap.163.com");
        assertThat(merged.mail().password()).isEmpty();
        assertThat(merged.db().mode()).isEqualTo(IntegrationSettings.MODE_H2);
        assertThat(merged.db().password()).isEqualTo("db-new");

        IntegrationSettings reloaded = service.load();
        assertThat(reloaded.amap().apiKey()).isEqualTo("amap-old");
        assertThat(reloaded.mail().password()).isEmpty();
        assertThat(reloaded.db().password()).isEqualTo("db-new");
    }
}
