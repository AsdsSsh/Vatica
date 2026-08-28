package com.example.vatica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 迭代 32A/32B：工具语义发现和按需暴露的运行时护栏。
 *
 * <p>这些参数只控制候选工具的可见性和检索成本，不授予任何工具权限。
 * 权限仍由任务角色、Skill manifest 和执行期回调共同决定。</p>
 */
@ConfigurationProperties(prefix = "vatica.agent.tools.discovery")
public record ToolDiscoveryProperties(Boolean enabled, int maxInitialTools, int maxSearchResults,
        int maxSearchExpansions, int maxInitialSchemaTokens, int maxSearchSchemaTokens,
        double lexicalWeight, double semanticWeight, double minimumScore) {

    public static final int DEFAULT_MAX_INITIAL_TOOLS = 12;
    public static final int DEFAULT_MAX_SEARCH_RESULTS = 8;
    public static final int DEFAULT_MAX_SEARCH_EXPANSIONS = 1;
    public static final int DEFAULT_MAX_INITIAL_SCHEMA_TOKENS = 6_000;
    public static final int DEFAULT_MAX_SEARCH_SCHEMA_TOKENS = 8_000;
    public static final double DEFAULT_LEXICAL_WEIGHT = 0.45d;
    public static final double DEFAULT_SEMANTIC_WEIGHT = 0.55d;

    public ToolDiscoveryProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxInitialTools = positive(maxInitialTools, DEFAULT_MAX_INITIAL_TOOLS);
        maxSearchResults = positive(maxSearchResults, DEFAULT_MAX_SEARCH_RESULTS);
        maxSearchExpansions = Math.max(0, maxSearchExpansions);
        maxInitialSchemaTokens = positive(maxInitialSchemaTokens, DEFAULT_MAX_INITIAL_SCHEMA_TOKENS);
        maxSearchSchemaTokens = positive(maxSearchSchemaTokens, DEFAULT_MAX_SEARCH_SCHEMA_TOKENS);
        lexicalWeight = finiteNonNegative(lexicalWeight, DEFAULT_LEXICAL_WEIGHT);
        semanticWeight = finiteNonNegative(semanticWeight, DEFAULT_SEMANTIC_WEIGHT);
        if (lexicalWeight == 0d && semanticWeight == 0d) {
            lexicalWeight = DEFAULT_LEXICAL_WEIGHT;
            semanticWeight = DEFAULT_SEMANTIC_WEIGHT;
        }
        minimumScore = finiteNonNegative(minimumScore, 0d);
        minimumScore = Math.min(1d, minimumScore);
    }

    public ToolDiscoveryProperties() {
        this(true, 0, 0, DEFAULT_MAX_SEARCH_EXPANSIONS, 0, 0,
                DEFAULT_LEXICAL_WEIGHT, DEFAULT_SEMANTIC_WEIGHT, 0d);
    }

    public boolean enabledValue() {
        return Boolean.TRUE.equals(enabled);
    }

    public double normalizedLexicalWeight() {
        return lexicalWeight / (lexicalWeight + semanticWeight);
    }

    public double normalizedSemanticWeight() {
        return semanticWeight / (lexicalWeight + semanticWeight);
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private static double finiteNonNegative(double value, double fallback) {
        return Double.isFinite(value) && value >= 0d ? value : fallback;
    }
}
