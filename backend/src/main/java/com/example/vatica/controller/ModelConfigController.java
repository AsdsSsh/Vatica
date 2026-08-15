package com.example.vatica.controller;

import java.util.List;
import java.util.Map;

import com.example.vatica.config.ModelConfigService;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    public List<ModelSlot> list() {
        return config.slots();
    }

    @PutMapping
    public List<ModelSlot> save(@RequestBody List<ModelSlot> slots) {
        return config.save(slots);
    }

    /** 连通性测试：测试的是界面当前编辑的槽位内容，不要求先保存。 */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody ModelSlot slot) {
        try {
            String reply = registry.testConnection(slot);
            return Map.of("ok", true, "reply", reply);
        }
        catch (Exception e) {
            return Map.of("ok", false, "error", rootMessage(e));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    /** 校验失败（IllegalArgumentException）→ 400 + 用户可读消息（界面直接展示）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleValidation(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "参数不合法" : e.getMessage()));
    }

    /** 存储失败等运行时错误 → 500 + 根因消息。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleStorage(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", rootMessage(e)));
    }
}
