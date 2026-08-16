package com.example.vatica.controller;

import java.util.List;
import java.util.Map;

import com.example.vatica.config.ModelConfigService;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.ModelSlotView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置接口（迭代 8.5 模型配置中心）：
 * <ul>
 *   <li>{@code GET /api/models}：当前生效槽位（设置界面数据源；本机桌面应用，key 明文返回便于编辑）</li>
 *   <li>{@code PUT /api/models}：保存槽位列表（校验通过写盘 models.json，即时生效）</li>
 *   <li>{@code POST /api/models/test}：连通性测试（不保存、不带工具，返回 ok/reply 或 ok/error）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/models")
public class ModelConfigController {

    private final ModelConfigService config;
    private final ModelRegistry registry;

    public ModelConfigController(ModelConfigService config, ModelRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @GetMapping
    public List<ModelSlotView> list() {
        return config.slots().stream().map(this::view).toList();
    }

    @PutMapping
    public List<ModelSlotView> save(@RequestBody List<ModelSlot> slots) {
        return config.save(slots).stream().map(this::view).toList();
    }

    private ModelSlotView view(ModelSlot slot) {
        String key = slot.apiKey() == null ? "" : slot.apiKey();
        boolean set = !key.isBlank();
        String hint = set
                ? "…" + (key.length() <= 4 ? key : key.substring(key.length() - 4))
                : null;
        return new ModelSlotView(slot.id(), slot.name(), slot.protocol(), slot.baseUrl(), slot.model(),
                slot.temperature(), slot.enabled(), set, hint);
    }

    /** 连通性测试：测试的是界面当前编辑的槽位内容，不要求先保存。 */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody ModelSlot slot) {
        try {
            String reply = registry.testConnection(slot);
            return Map.of("ok", true, "reply", reply);
        }
        catch (Exception e) {
            return Map.of("ok", false, "error", ApiErrors.rootMessage(e));
        }
    }
}
