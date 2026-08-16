package com.example.vatica.config;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.example.vatica.secret.MasterKeyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 外部服务配置服务（迭代 13 I13-9）：读密文配置 + 保存。
 * 数据库/AMAP 端点启动期注入，保存后重启生效；邮件工具在迭代 13 也按重启生效处理。
 */
@Service
public class IntegrationSettingsService {

    private final AppStateProperties appProps;
    private final MasterKeyProvider masterKey;
    private final ObjectMapper mapper;

    public IntegrationSettingsService(AppStateProperties appProps, MasterKeyProvider masterKey, ObjectMapper mapper) {
        this.appProps = appProps;
        this.masterKey = masterKey;
        this.mapper = mapper;
    }

    public IntegrationSettings load() {
        IntegrationSettings loaded = IntegrationSettings.load(Path.of(appProps.stateDir()), masterKey, mapper);
        return loaded == null ? IntegrationSettings.defaults() : loaded;
    }

    /**
     * 保存（迭代 13 I13-9；迭代 13.5 合并语义）：
     * 密钥字段 null = 保持现值（表单留空不代表清除），空串 = 清除，非空 = 覆写；
     * 其余字段以请求为准。这样"只填了 AMAP Key、没动邮件"的保存不会清掉邮件密码。
     */
    public IntegrationSettings save(IntegrationSettings request) {
        IntegrationSettings normalized = normalize(request, load());
        IntegrationSettings.save(Path.of(appProps.stateDir()), masterKey, mapper, normalized);
        return normalized;
    }

    private static IntegrationSettings normalize(IntegrationSettings request, IntegrationSettings current) {
        if (current == null) {
            current = IntegrationSettings.defaults();
        }
        if (request == null) {
            return current;
        }
        IntegrationSettings.Amap amap = request.amap() == null ? current.amap() : request.amap();
        IntegrationSettings.Mail mail = request.mail() == null ? current.mail() : request.mail();
        IntegrationSettings.Db db = request.db() == null ? current.db() : request.db();
        return new IntegrationSettings(
                new IntegrationSettings.Amap(mergeSecret(amap.apiKey(), current.amap().apiKey())),
                new IntegrationSettings.Mail(mail.imapHost(), mail.imapPort(), mail.smtpHost(), mail.smtpPort(),
                        mail.username(), mergeSecret(mail.password(), current.mail().password())),
                new IntegrationSettings.Db(db.mode(), db.host(), db.port(), db.database(), db.username(),
                        mergeSecret(db.password(), current.db().password())));
    }

    /** null = keep；空串 = clear（前端"清除"开关传空串）；非空 = 覆写。 */
    private static String mergeSecret(String requested, String current) {
        return requested == null ? (current == null ? "" : current) : requested;
    }
}
