package com.example.vatica.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.vatica.config.ModelSlot;
import io.agentscope.core.tool.AgentTool;

/**
 * 迭代 17A：生产角色注册表。角色只描述执行职责、模型能力标签与工具白名单，
 * 任务状态、身份、权限、HITL 和持久化仍由 Vatica 负责。
 */
@Component
public class AgentRegistry {

    public static final String GENERAL = "general";

    private final Map<String, AgentDefinition> definitions;

    public AgentRegistry() {
        Map<String, AgentDefinition> roles = new LinkedHashMap<>();
        roles.put("document", new AgentDefinition("document", "文档 Agent",
                "只负责生成 Word 或 Excel 文档；需要读取、整理或计算时应由其他步骤先产出输入。",
                ModelSlot.CAP_CHAT_REASON, Set.of("create_word_report", "create_excel_stats"), Set.of(), false));
        roles.put("pim", new AgentDefinition("pim", "个人事务 Agent",
                "只负责日历、待办和邮件相关操作，副作用操作必须服从 Vatica 的审批结果。",
                ModelSlot.CAP_CHAT_REASON,
                Set.of("calendar_query", "calendar_create", "calendar_import",
                        "todo_add", "todo_list", "todo_complete", "todo_remind",
                        "mail_query", "mail_send"), Set.of(), false));
        roles.put("workspace", new AgentDefinition("workspace", "工作区 Agent",
                "只负责已授权工作区中的文件读取、写入、列举和授权根目录查询。",
                ModelSlot.CAP_CHAT_REASON,
                Set.of("read_file", "write_file", "list_files", "list_workspace_roots"), Set.of(), false));
        roles.put("research", new AgentDefinition("research", "研究分析 Agent",
                "只负责知识库、高德地图 MCP 查询、计算与文本统计，不得编造工具未返回的数据；"
                        + "使用知识库时必须保留 C1/C2 引用编号。",
                ModelSlot.CAP_CHAT_REASON, Set.of("calculator", "text_stats", "search_knowledge_base"),
                Set.of("maps_", "amap_"), false));
        roles.put(GENERAL, new AgentDefinition(GENERAL, "通用 Agent",
                "负责无法归入专门角色的步骤；仍须遵守全部身份、权限、审批和工具规则。",
                ModelSlot.CAP_CHAT_REASON, Set.of(), Set.of(), true));
        definitions = Map.copyOf(roles);
    }

    /** 空值或未知角色统一回退 general，兼容旧计划与模型幻觉字段。 */
    public AgentDefinition resolve(String requestedId) {
        String id = requestedId == null ? "" : requestedId.trim().toLowerCase(Locale.ROOT);
        return definitions.getOrDefault(id, definitions.get(GENERAL));
    }

    public String normalizeId(String requestedId) {
        return resolve(requestedId).id();
    }

    public List<AgentDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /** 迭代 20A：Skill manifest 注册时复用角色机械门禁，禁止声明角色不可用的工具。 */
    public boolean allowsTool(String requestedId, String toolName) {
        return toolName != null && resolve(requestedId).allows(toolName);
    }

    /** 迭代 22B：角色白名单在 AgentScope 原生工具注入前机械裁剪。 */
    public AgentTool[] allowedTools(String requestedId, AgentTool[] tools) {
        AgentDefinition role = resolve(requestedId);
        if (tools == null || tools.length == 0) {
            return new AgentTool[0];
        }
        if (role.allowAllTools()) {
            return tools.clone();
        }
        return java.util.Arrays.stream(tools).filter(tool -> role.allows(tool.getName()))
                .toArray(AgentTool[]::new);
    }

    /** Planner 可直接消费的稳定角色清单，避免提示词和注册表漂移。 */
    public String plannerPrompt() {
        return definitions.values().stream()
                .map(role -> role.id() + "=" + role.displayName() + role.toolHint())
                .reduce((left, right) -> left + " / " + right)
                .orElse(GENERAL);
    }

    public record AgentDefinition(String id, String displayName, String systemPrompt,
            String modelCapability, Set<String> allowedTools, Set<String> allowedPrefixes,
            boolean allowAllTools) {
        public AgentDefinition {
            allowedTools = Set.copyOf(allowedTools);
            allowedPrefixes = Set.copyOf(allowedPrefixes);
        }

        boolean allows(String toolName) {
            return allowAllTools || allowedTools.contains(toolName)
                    || allowedPrefixes.stream().anyMatch(toolName::startsWith);
        }

        String toolHint() {
            if (allowAllTools) {
                return "[全部当前工具]";
            }
            return "[" + String.join(",", allowedTools)
                    + (allowedPrefixes.isEmpty() ? "" : "," + String.join("*,", allowedPrefixes) + "*") + "]";
        }
    }
}
