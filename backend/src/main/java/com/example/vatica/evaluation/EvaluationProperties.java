package com.example.vatica.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 迭代 18C：固定评测进入下一迭代前的最小质量门禁。 */
@ConfigurationProperties(prefix = "vatica.evaluation")
public record EvaluationProperties(int minSamplesPerCase, double minPassRate,
        double minAverageScore, double maxFailedToolRate) {

    public static final int DEFAULT_MIN_SAMPLES = 1;
    public static final double DEFAULT_MIN_PASS_RATE = 0.8;
    public static final double DEFAULT_MIN_AVERAGE_SCORE = 70.0;
    public static final double DEFAULT_MAX_FAILED_TOOL_RATE = 0.1;

    public EvaluationProperties {
        if (minSamplesPerCase < 1 || minSamplesPerCase > 100) {
            minSamplesPerCase = DEFAULT_MIN_SAMPLES;
        }
        if (minPassRate <= 0 || minPassRate > 1) {
            minPassRate = DEFAULT_MIN_PASS_RATE;
        }
        if (minAverageScore < 0 || minAverageScore > 100) {
            minAverageScore = DEFAULT_MIN_AVERAGE_SCORE;
        }
        if (maxFailedToolRate < 0 || maxFailedToolRate > 1) {
            maxFailedToolRate = DEFAULT_MAX_FAILED_TOOL_RATE;
        }
    }
}
