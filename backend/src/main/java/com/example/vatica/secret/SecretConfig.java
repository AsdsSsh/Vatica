package com.example.vatica.secret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.vatica.config.AppStateProperties;

/**
 * 迭代 13 I13-1：主密钥与信封加密装配。
 */
@Configuration
public class SecretConfig {

    @Bean
    MasterKeyProvider masterKeyProvider(AppStateProperties appProps) {
        return new FileMasterKeyProvider(appProps);
    }

    @Bean
    SecretCrypto secretCrypto(MasterKeyProvider masterKeyProvider) {
        return new SecretCrypto(masterKeyProvider);
    }
}
