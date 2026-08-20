package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型选择器配置（{@code vatica.model.*}，迭代 7 I7-5）：主模型 DeepSeek（vatica.model.openai.*），
 * 备用模型通义千问（OpenAI 兼容，qwen.*）。
 *
 * @param qwen 备用模型配置
 */
@ConfigurationProperties(prefix = "vatica.model")
public record ModelProperties(Qwen qwen) {

    public ModelProperties {
        if (qwen == null) {
            qwen = new Qwen("", "", "", null);
        }
    }

    public record Qwen(String apiKey, String baseUrl, String model, Double temperature) {

        public Qwen {
            if (apiKey == null) {
                apiKey = "";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
            }
            if (model == null || model.isBlank()) {
                model = "qwen-plus";
            }
            if (temperature == null) {
                temperature = 0.7;
            }
        }

        /** 是否已配置（apiKey 非空才可路由请求）。 */
        public boolean configured() {
            return !apiKey.isBlank();
        }
    }
}
