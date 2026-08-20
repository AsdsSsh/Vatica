package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 迭代 23D：模型选择器必须区分“启用”与“实际可调用”。 */
class ModelRegistryAvailabilityTest {

    @Test
    void onlyTreatsCredentialedOrLocalEnabledSlotsAsCallable() {
        ModelSlot remoteWithoutKey = new ModelSlot("remote", "Remote", "openai",
                "https://api.example.test", "", "chat", 0.2, true);
        ModelSlot localWithoutKey = new ModelSlot("local", "Local", "openai",
                "http://127.0.0.1:11434/v1", "", "chat", 0.2, true);
        ModelSlot disabledWithKey = new ModelSlot("disabled", "Disabled", "openai",
                "https://api.example.test", "key", "chat", 0.2, false);

        assertThat(ModelRegistry.isCallable(remoteWithoutKey)).isFalse();
        assertThat(ModelRegistry.isCallable(localWithoutKey)).isTrue();
        assertThat(ModelRegistry.isCallable(disabledWithKey)).isFalse();
    }
}
