package com.example.vatica.agentscope;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.example.vatica.config.ModelSlot;

import io.agentscope.core.model.ModelContextWindows;

/**
 * 迭代 30A：使用 AgentScope 的模型窗口目录，避免把所有模型错误地按 16K 处理。
 *
 * <p>未知的第三方兼容端点仍使用保守回退值。窗口大小只用于本地预算与裁剪，
 * 不代表平台一定接受同样大小的请求；真正的模型错误仍必须由调用链处理。</p>
 */
public final class AgentScopeContextWindowResolver {

    public static final int FALLBACK_CONTEXT_WINDOW = 16_000;

    private AgentScopeContextWindowResolver() {
    }

    public static int resolve(ModelSlot slot) {
        if (slot == null) {
            return FALLBACK_CONTEXT_WINDOW;
        }
        String model = lower(slot.model());
        String endpoint = lower(slot.baseUrl());
        for (Map<String, Integer> candidates : preferredMaps(slot.protocol(), endpoint)) {
            int value = ModelContextWindows.lookup(model, candidates);
            if (value > 0) {
                return value;
            }
        }
        // 自定义 OpenAI 兼容端点可能没有可识别的 provider URL；已知模型名仍可命中目录。
        for (Map<String, Integer> candidates : allMaps()) {
            int value = ModelContextWindows.lookup(model, candidates);
            if (value > 0) {
                return value;
            }
        }
        return FALLBACK_CONTEXT_WINDOW;
    }

    private static List<Map<String, Integer>> preferredMaps(String protocol, String endpoint) {
        if ("anthropic".equalsIgnoreCase(protocol)) {
            return List.of(ModelContextWindows.ANTHROPIC);
        }
        if (endpoint.contains("dashscope") || endpoint.contains("aliyuncs")) {
            return List.of(ModelContextWindows.DASHSCOPE);
        }
        if (endpoint.contains("deepseek")) {
            return List.of(ModelContextWindows.DEEPSEEK);
        }
        if (endpoint.contains("zhipu") || endpoint.contains("bigmodel")) {
            return List.of(ModelContextWindows.GLM);
        }
        if (endpoint.contains("minimax")) {
            return List.of(ModelContextWindows.MINIMAX);
        }
        if (endpoint.contains("moonshot") || endpoint.contains("kimi")) {
            return List.of(ModelContextWindows.KIMI);
        }
        if (endpoint.contains("google") || endpoint.contains("gemini")) {
            return List.of(ModelContextWindows.GEMINI);
        }
        return List.of(ModelContextWindows.OPENAI);
    }

    private static List<Map<String, Integer>> allMaps() {
        return List.of(ModelContextWindows.DASHSCOPE, ModelContextWindows.OPENAI,
                ModelContextWindows.DEEPSEEK, ModelContextWindows.GLM, ModelContextWindows.MINIMAX,
                ModelContextWindows.KIMI, ModelContextWindows.ANTHROPIC, ModelContextWindows.GEMINI);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
