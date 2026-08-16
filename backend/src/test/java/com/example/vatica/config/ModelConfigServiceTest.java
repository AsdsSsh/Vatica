package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
        ModelCredentialStore credentials = mock(ModelCredentialStore.class);
        when(credentials.resolve(anyString())).thenReturn(Optional.empty());
        service = new ModelConfigService(
                new AppStateProperties(tempDir.toString()),
                new ObjectMapper(),
                new OpenAiDefaultsProperties("deep-key", "https://api.deepseek.com",
                        new OpenAiDefaultsProperties.Chat("deepseek-v4-flash", 0.7)),
                new ModelProperties(new ModelProperties.Qwen("", "", "", null)),
                credentials);
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

    /** 迭代 10 I10-4：启用的槽位必须填 Base URL（旧实现只校验 model，会漏掉空端点）。 */
    @Test
    void validateRejectsEnabledSlotWithoutBaseUrl() {
        ModelSlot bad = new ModelSlot("x", "缺端点", ModelSlot.PROTOCOL_OPENAI,
                "  ", "k", "m", 0.7, true);
        assertThatThrownBy(() -> service.save(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base URL");
    }

    /** 迭代 10 I10-4：温度越界 / 缺失（启用槽位）都拒绝，与前端 0-2 输入一致。 */
    @Test
    void validateRejectsIllegalTemperature() {
        ModelSlot tooHot = new ModelSlot("x", "过热", ModelSlot.PROTOCOL_OPENAI,
                "https://example.com", "k", "m", 2.1, true);
        ModelSlot missing = new ModelSlot("y", "缺温度", ModelSlot.PROTOCOL_OPENAI,
                "https://example.com", "k", "m", null, true);

        assertThatThrownBy(() -> service.save(List.of(tooHot)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("温度");
        assertThatThrownBy(() -> service.save(List.of(missing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("温度");
    }

    /** 迭代 10 I10-4：保存时归一化——id 转小写、协议转小写、字段 trim，读回即归一化值。 */
    @Test
    void saveNormalizesSlotFields() {
        service.save(List.of(new ModelSlot("  DeepSeek  ", " DeepSeek V4 ", "OPENAI",
                " https://api.deepseek.com ", " sk-key ", " deepseek-v4-flash ", 0.8, true)));

        List<ModelSlot> slots = service.slots();

        assertThat(slots).hasSize(1);
        ModelSlot s = slots.get(0);
        assertThat(s.id()).isEqualTo("deepseek");
        assertThat(s.name()).isEqualTo("DeepSeek V4");
        assertThat(s.protocol()).isEqualTo(ModelSlot.PROTOCOL_OPENAI);
        assertThat(s.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(s.model()).isEqualTo("deepseek-v4-flash");
        assertThat(s.temperature()).isEqualTo(0.8);
    }

    /** 迭代 10 I10-4：id 重复按小写归一化后判定（"DS" 与 "ds" 不能并存）。 */
    @Test
    void validateRejectsCaseInsensitiveDuplicateId() {
        assertThatThrownBy(() -> service.save(List.of(slot("DS", true), slot("ds", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
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
