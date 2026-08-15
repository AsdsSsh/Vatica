package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.vatica.tool.FileToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 模型配置存取单测（迭代 8.5）：默认回退 / 界面配置优先 / 损坏回退 / 保存校验。 */
class ModelConfigServiceTest {

    @TempDir
    Path tempDir;

    private Path modelsFile;
    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        modelsFile = tempDir.resolve("models.json");
        service = new ModelConfigService(
                new FileToolProperties(tempDir.toString(), 524288),
                new ObjectMapper(),
                new OpenAiDefaultsProperties("deep-key", "https://api.deepseek.com",
                        new OpenAiDefaultsProperties.Chat("deepseek-v4-flash", 0.7)),
                new ModelProperties(new ModelProperties.Qwen("", "", "", null)));
    }

    private ModelSlot slot(String id, boolean enabled) {
        return new ModelSlot(id, "模型 " + id, ModelSlot.PROTOCOL_OPENAI,
                "https://example.com/v1", id + "-key", id + "-model", 0.7, enabled);
    }

    /** 无配置文件：回退默认槽位（deepseek 恒启用；qwen 无 key 则禁用——迭代 7 行为）。 */
    @Test
    void defaultsWhenNoFile() {
        List<ModelSlot> slots = service.slots();

        assertThat(slots).hasSize(2);
        ModelSlot deepseek = slots.get(0);
        assertThat(deepseek.id()).isEqualTo("deepseek");
        assertThat(deepseek.enabled()).isTrue();
        assertThat(deepseek.apiKey()).isEqualTo("deep-key");
        assertThat(deepseek.model()).isEqualTo("deepseek-v4-flash");
        ModelSlot qwen = slots.get(1);
        assertThat(qwen.id()).isEqualTo("qwen");
        assertThat(qwen.enabled()).isFalse();   // 备用模型无 key → 未配置
    }

    /** 界面配置优先（迭代 8.5 决策）：保存后文件配置覆盖默认槽位。 */
    @Test
    void savedFileOverridesDefaults() {
        service.save(List.of(slot("custom", true)));

        List<ModelSlot> slots = service.slots();

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).id()).isEqualTo("custom");
        assertThat(Files.exists(modelsFile)).isTrue();
    }

    /** 保存回读一致（含 anthropic 协议槽位）。 */
    @Test
    void saveRoundTripsBothProtocols() {
        ModelSlot claude = new ModelSlot("claude", "Claude Sonnet", ModelSlot.PROTOCOL_ANTHROPIC,
                "https://api.anthropic.com", "ant-key", "claude-sonnet-4-6", 0.3, true);
        service.save(List.of(slot("openai-1", true), claude));

        List<ModelSlot> slots = service.slots();

        assertThat(slots).hasSize(2);
        assertThat(slots.get(1).protocol()).isEqualTo(ModelSlot.PROTOCOL_ANTHROPIC);
        assertThat(slots.get(1).temperature()).isEqualTo(0.3);
    }

    /** 配置文件损坏：回退默认（不静默覆盖——用户数据安全约定，与 todos.json 同款）。 */
    @Test
    void corruptFileFallsBackToDefaultsAndKeepsFile() throws Exception {
        Files.writeString(modelsFile, "{ 不是合法 JSON", StandardCharsets.UTF_8);

        List<ModelSlot> slots = service.slots();

        assertThat(slots).hasSize(2);
        assertThat(slots.get(0).id()).isEqualTo("deepseek");
        assertThat(Files.readString(modelsFile, StandardCharsets.UTF_8)).contains("不是合法 JSON");
    }

    /** 文件内容为空列表：视为未配置，回退默认。 */
    @Test
    void emptyModelsFallsBackToDefaults() throws Exception {
        Files.writeString(modelsFile, "{\"version\":1,\"models\":[]}", StandardCharsets.UTF_8);

        assertThat(service.slots()).hasSize(2);
    }

    /** 校验：空列表拒绝。 */
    @Test
    void validateRejectsEmptyList() {
        assertThatThrownBy(() -> service.save(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少保留一个");
    }

    /** 校验：id 重复拒绝。 */
    @Test
    void validateRejectsDuplicateId() {
        assertThatThrownBy(() -> service.save(List.of(slot("a", true), slot("a", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    /** 校验：启用的槽位必须填模型 ID。 */
    @Test
    void validateRejectsEnabledSlotWithoutModel() {
        ModelSlot bad = new ModelSlot("x", "缺模型", ModelSlot.PROTOCOL_OPENAI,
                "https://example.com", "k", "", 0.7, true);
        assertThatThrownBy(() -> service.save(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型 ID");
    }

    /** 校验：不支持的协议拒绝。 */
    @Test
    void validateRejectsUnknownProtocol() {
        ModelSlot bad = new ModelSlot("x", "怪协议", "gemini", "https://example.com",
                "k", "m", 0.7, true);
        assertThatThrownBy(() -> service.save(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
    }
}
