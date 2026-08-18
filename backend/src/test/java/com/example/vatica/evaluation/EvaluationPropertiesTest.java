package com.example.vatica.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 迭代 18C：门禁配置越界时必须回到可解释默认值。 */
class EvaluationPropertiesTest {

    @Test
    void invalidValuesFallBackToDefaults() {
        EvaluationProperties properties = new EvaluationProperties(0, 2.0, -1, 2.0);

        assertThat(properties.minSamplesPerCase()).isEqualTo(1);
        assertThat(properties.minPassRate()).isEqualTo(0.8);
        assertThat(properties.minAverageScore()).isEqualTo(70.0);
        assertThat(properties.maxFailedToolRate()).isEqualTo(0.1);
    }
}
