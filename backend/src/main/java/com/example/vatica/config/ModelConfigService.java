package com.example.vatica.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.example.vatica.tool.PathSecurityGuard;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模型配置存取（迭代 8.5 模型配置中心）：界面配置存 {@code .vatica/models.json}
 * （迭代 11 从 data/ 迁入内部状态目录；打包模式即 {@code %APPDATA%\Vatica\.vatica\models.json}）。
 *
 * <p><b>优先级（已定决策 2026-08-15）</b>：界面配置优先——文件存在且非空即以其为准；
 * 文件不存在/为空/损坏时回退默认槽位（DeepSeek 走 {@code spring.ai.openai.*}、
 * 通义走 {@code vatica.model.qwen.*}，与迭代 7 的 yml/环境变量行为完全一致）。
 * 损坏文件不静默覆盖（与 todos.json 同款数据保护约定），记日志回退默认，用户保存时自然修复。
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);
    private static final String FILE_NAME = "models.json";

    private final AppStateProperties appProps;
    private final ObjectMapper objectMapper;
    private final OpenAiDefaultsProperties openAiDefaults;
    private final ModelProperties modelProps;

    public ModelConfigService(AppStateProperties appProps, ObjectMapper objectMapper,
            OpenAiDefaultsProperties openAiDefaults, ModelProperties modelProps) {
        this.appProps = appProps;
        this.objectMapper = objectMapper;
        this.openAiDefaults = openAiDefaults;
        this.modelProps = modelProps;
    }

    /** 当前生效的槽位列表（界面配置优先，回退默认）。 */
    public List<ModelSlot> slots() {
        Path file = resolveFile();
        if (!Files.exists(file)) {
            return defaults();
        }
        try {
            ConfigFile config = objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    ConfigFile.class);
            List<ModelSlot> models = config.models();
            if (models == null || models.isEmpty()) {
                return defaults();   // 空配置视为未配置（保留文件不动，等用户保存修复）
            }
            // 迭代 10 I10-4：读取路径与保存共用同一套归一化——手工改坏的配置同样回退默认而非带病运行
            return validate(models);
        }
        catch (Exception e) {
            log.error("模型配置 {} 读取失败，本次回退默认配置：{}", file, e.getMessage());
            return defaults();
        }
    }

    /** 保存（校验通过后写盘，写入即生效）。 */
    public List<ModelSlot> save(List<ModelSlot> slots) {
        List<ModelSlot> normalized = validate(slots);
        Path file = resolveFile();
        try {
            Files.writeString(file,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(new ConfigFile(1, normalized)),
                    StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("操作失败：无法保存模型配置。" + e.getMessage(), e);
        }
        return normalized;
    }

    /**
     * 槽位校验与归一化（迭代 10 I10-4）：id 小写唯一、name/baseUrl/apiKey/model 去首尾空白、
     * 协议 lowercase；启用的槽位 baseUrl/model/temperature 必须齐全，温度统一限定 0-2
     * （与前端输入控件一致）。返回归一化后的新列表——旧实现用副本校验却返回原始值，
     * 大写协议能保存但在 ModelRegistry 运行时 switch 失败。
     */
    List<ModelSlot> validate(List<ModelSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException("操作失败：至少保留一个模型槽位。");
        }
        List<ModelSlot> normalized = new ArrayList<>(slots.size());
        Set<String> seen = new LinkedHashSet<>();
        for (ModelSlot s : slots) {
            String id = s.id() == null ? "" : s.id().trim().toLowerCase(Locale.ROOT);
            String name = s.name() == null ? "" : s.name().trim();
            String protocol = s.protocol() == null ? "" : s.protocol().toLowerCase(Locale.ROOT);
            String baseUrl = s.baseUrl() == null ? "" : s.baseUrl().trim();
            String apiKey = s.apiKey() == null ? "" : s.apiKey().trim();
            String model = s.model() == null ? "" : s.model().trim();
            Double temperature = s.temperature() == null ? 0.7 : s.temperature();

            if (id.isEmpty() || name.isEmpty()) {
                throw new IllegalArgumentException("操作失败：模型槽位的标识与名称不能为空。");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("操作失败：模型标识重复（" + id + "）。");
            }
            if (!protocol.equals(ModelSlot.PROTOCOL_OPENAI) && !protocol.equals(ModelSlot.PROTOCOL_ANTHROPIC)) {
                throw new IllegalArgumentException(
                        "操作失败：不支持的协议（" + s.protocol() + "），仅支持 openai / anthropic。");
            }
            if (temperature < 0 || temperature > 2) {
                throw new IllegalArgumentException(
                        "操作失败：温度必须在 0-2 之间（" + name + "）。");
            }
            if (s.enabled()) {
                if (baseUrl.isEmpty()) {
                    throw new IllegalArgumentException("操作失败：已启用的模型必须填写 Base URL（" + name + "）。");
                }
                if (model.isEmpty()) {
                    throw new IllegalArgumentException("操作失败：已启用的模型必须填写模型 ID（" + name + "）。");
                }
                if (s.temperature() == null) {
                    throw new IllegalArgumentException("操作失败：已启用的模型必须填写温度（" + name + "）。");
                }
            }
            normalized.add(new ModelSlot(id, name, protocol, baseUrl, apiKey, model,
                    temperature, s.enabled()));
        }
        return normalized;
    }

    /** 默认槽位（文件配置缺失时）：与迭代 7 的 yml/环境变量行为一致。 */
    List<ModelSlot> defaults() {
        String deepKey = openAiDefaults.apiKey() == null ? "" : openAiDefaults.apiKey();
        String deepModel = chatModel(openAiDefaults.chat(), "deepseek-v4-flash");
        Double deepTemp = chatTemperature(openAiDefaults.chat());
        String deepBase = openAiDefaults.baseUrl() == null || openAiDefaults.baseUrl().isBlank()
                ? "https://api.deepseek.com"
                : openAiDefaults.baseUrl();
        // 主模型默认始终启用（迭代 7 行为：configured 恒 true）；备用模型按 key 是否配置
        ModelSlot deepseek = new ModelSlot("deepseek", "DeepSeek " + deepModel, ModelSlot.PROTOCOL_OPENAI,
                deepBase, deepKey, deepModel, deepTemp, true);
        ModelSlot qwen = new ModelSlot("qwen", "通义千问 " + modelProps.qwen().model(), ModelSlot.PROTOCOL_OPENAI,
                modelProps.qwen().baseUrl(), modelProps.qwen().apiKey(), modelProps.qwen().model(),
                modelProps.qwen().temperature(), modelProps.qwen().configured());
        return List.of(deepseek, qwen);
    }

    private static String chatModel(OpenAiDefaultsProperties.Chat chat, String fallback) {
        return chat == null || chat.model() == null || chat.model().isBlank() ? fallback : chat.model();
    }

    private static Double chatTemperature(OpenAiDefaultsProperties.Chat chat) {
        return chat == null || chat.temperature() == null ? 0.7 : chat.temperature();
    }

    private Path resolveFile() {
        return PathSecurityGuard.resolveForWrite(Path.of(appProps.stateDir()), FILE_NAME);
    }

    /** models.json 顶层结构（带版本号，便于将来迁移）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigFile(int version, List<ModelSlot> models) {
    }
}
