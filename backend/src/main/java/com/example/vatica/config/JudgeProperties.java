package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 质量评测配置（{@code vatica.judge.*}，迭代 5.5 新增）。
 *
 * @param passThreshold Judge 评分 >= 该值判 PASS（0-100，越界回退 70）
 * @param maxAutoRework 低分自动返工上限（0 表示关闭自动返工、不合格直接交人工；越界回退 2）
 */
@ConfigurationProperties(prefix = "vatica.judge")
public record JudgeProperties(int passThreshold, int maxAutoRework) {

    public static final int DEFAULT_PASS_THRESHOLD = 70;
    public static final int DEFAULT_MAX_AUTO_REWORK = 2;

    public JudgeProperties {
        if (passThreshold < 0 || passThreshold > 100) {
            passThreshold = DEFAULT_PASS_THRESHOLD;
        }
        if (maxAutoRework < 0 || maxAutoRework > 5) {
            maxAutoRework = DEFAULT_MAX_AUTO_REWORK;
        }
    }
}
