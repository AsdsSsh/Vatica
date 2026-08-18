package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** 迭代 18：任务超时配置的默认值与非法值回退。 */
class TaskReliabilityPropertiesTest {

    @Test
    void keepsPositiveTimeout() {
        TaskReliabilityProperties props = new TaskReliabilityProperties(Duration.ofSeconds(30));

        assertThat(props.stepTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void invalidTimeoutFallsBackToFiveMinutes() {
        assertThat(new TaskReliabilityProperties(null).stepTimeout())
                .isEqualTo(TaskReliabilityProperties.DEFAULT_STEP_TIMEOUT);
        assertThat(new TaskReliabilityProperties(Duration.ZERO).stepTimeout())
                .isEqualTo(TaskReliabilityProperties.DEFAULT_STEP_TIMEOUT);
    }
}
