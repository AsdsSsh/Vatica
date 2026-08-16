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

    public IntegrationSettings save(IntegrationSettings settings) {
        IntegrationSettings normalized = normalize(settings);
        IntegrationSettings.save(Path.of(appProps.stateDir()), masterKey, mapper, normalized);
        return normalized;
    }

    private static IntegrationSettings normalize(IntegrationSettings settings) {
        IntegrationSettings current = settings;
        if (current == null) {
            current = IntegrationSettings.defaults();
        }
        return current;
    }
}
