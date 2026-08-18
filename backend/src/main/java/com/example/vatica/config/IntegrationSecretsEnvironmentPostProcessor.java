package com.example.vatica.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import com.example.vatica.secret.FileMasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 迭代 13 I13-9：启动早期把 `.vatica/integrations.json` 解密后注入 Environment，
 * 覆盖 AMAP endpoint / 邮件 / DataSource 的 yml 默认值。文件不存在则保持 yml 默认。
 * 数据库与 AMAP 配置修改后重启生效。
 */
public class IntegrationSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            String stateDir = environment.getProperty("vatica.app.state-dir", "./.vatica");
            ObjectMapper mapper = new ObjectMapper();
            FileMasterKeyProvider masterKey = new FileMasterKeyProvider(new AppStateProperties(stateDir));
            IntegrationSettings settings = IntegrationSettings.load(Path.of(stateDir), masterKey, mapper);
            if (settings == null) {
                return;
            }
            Map<String, Object> props = new LinkedHashMap<>();

            if (settings.amap().apiKey() != null && !settings.amap().apiKey().isBlank()) {
                props.put("spring.ai.mcp.client.streamable-http.connections.amap.endpoint",
                        "/mcp?key=" + settings.amap().apiKey());
            }

            IntegrationSettings.Mail mail = settings.mail();
            props.put("vatica.mail.imap-host", mail.imapHost());
            props.put("vatica.mail.imap-port", mail.imapPort());
            props.put("vatica.mail.smtp-host", mail.smtpHost());
            props.put("vatica.mail.smtp-port", mail.smtpPort());
            props.put("vatica.mail.username", mail.username());
            props.put("vatica.mail.password", mail.password());

            IntegrationSettings.Db db = settings.db();
            if (IntegrationSettings.MODE_H2.equalsIgnoreCase(db.mode())) {
                props.put("spring.datasource.url",
                        "jdbc:h2:file:" + stateDir + "/vatica-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
                props.put("spring.datasource.username", "sa");
                props.put("spring.datasource.password", "");
            } else if (IntegrationSettings.MODE_MYSQL.equalsIgnoreCase(db.mode())) {
                // 兼容已有 integrations.json；新建配置默认走 PostgreSQL。
                props.put("spring.datasource.url", "jdbc:mysql://" + db.host() + ":" + db.port()
                        + "/" + db.database() + "?createDatabaseIfNotExist=true&useUnicode=true"
                        + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
                props.put("spring.datasource.username", db.username());
                props.put("spring.datasource.password", db.password());
            } else {
                props.put("spring.datasource.url", "jdbc:postgresql://" + db.host() + ":" + db.port()
                        + "/" + db.database());
                props.put("spring.datasource.username", db.username());
                props.put("spring.datasource.password", db.password());
            }

            environment.getPropertySources().addFirst(new MapPropertySource("vaticaIntegrationSettings", props));
        } catch (Exception e) {
            // 密文损坏时保持 yml 默认，不让启动失败；设置页可重新保存
        }
    }
}
