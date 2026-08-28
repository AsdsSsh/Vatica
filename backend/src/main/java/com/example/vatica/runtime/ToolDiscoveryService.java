package com.example.vatica.runtime;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.vatica.config.ToolDiscoveryProperties;
import com.example.vatica.context.TokenEstimator;
import com.example.vatica.knowledge.EmbeddingGateway;
import com.example.vatica.knowledge.LocalHashEmbeddingModel;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 迭代 32A/32B：工具描述目录、混合召回和一次性渐进发现。
 *
 * <p>本类只负责回答“哪些候选工具值得挂载”。它不能授予权限：调用方必须先把工具
 * 经过角色、Skill 和权限包装，再把允许集合传入 {@link #open}。执行期仍由
 * {@code PermissionBoundToolCallbacks} 和 AgentScope ToolGroup 做最终校验。</p>
 */
@Service
public class ToolDiscoveryService {

    public static final String SEARCH_TOOLS_NAME = "search_tools";
    private static final int MAX_QUERY_CHARS = 500;
    private static final int MAX_DESCRIPTION_CHARS = 220;
    private static final int MAX_RESULT_CHARS = 3_500;
    private static final int DEFAULT_FALLBACK_EMBEDDING_DIMENSIONS = 256;

    private final EmbeddingGateway embedding;
    private final ToolDiscoveryProperties properties;
    private final Map<String, float[]> vectorCache = new ConcurrentHashMap<>();

    /** Spring 装配使用 ObjectProvider，便于知识库被裁剪时仍能启动聊天服务。 */
    @Autowired
    public ToolDiscoveryService(ObjectProvider<EmbeddingGateway> embedding,
            ToolDiscoveryProperties properties) {
        this(embedding == null ? null : embedding.getIfAvailable(), properties);
    }

    public ToolDiscoveryService(EmbeddingGateway embedding, ToolDiscoveryProperties properties) {
        this.embedding = embedding == null ? new LocalHashEmbeddingModel(DEFAULT_FALLBACK_EMBEDDING_DIMENSIONS)
                : embedding;
        this.properties = properties == null ? new ToolDiscoveryProperties() : properties;
    }

    /** 供无 Spring 的确定性单测和兼容构造器使用。 */
    public ToolDiscoveryService() {
        this((EmbeddingGateway) null, new ToolDiscoveryProperties());
    }

    /**
     * 为一个请求建立隔离的发现会话。allowedNames 是能力硬过滤结果；空集合表示全部候选，
     * 与旧调用方的空白名单语义一致。需要显式“零工具”时请传 {@code restrict=true} 的重载。
     */
    public DiscoverySession open(AgentTool[] tools, String initialQuery,
            Collection<String> allowedNames) {
        return open(tools, initialQuery, allowedNames, false);
    }

    public DiscoverySession open(AgentTool[] tools, String initialQuery,
            Collection<String> allowedNames, boolean restrict) {
        List<AgentTool> candidates = distinct(tools);
        Set<String> allowed = normalizeNames(allowedNames);
        if (restrict) {
            candidates = candidates.stream().filter(tool -> allowed.contains(tool.getName())).toList();
        } else if (!allowed.isEmpty()) {
            candidates = candidates.stream().filter(tool -> allowed.contains(tool.getName())).toList();
        }
        return new DiscoverySession(candidates, initialQuery);
    }

    /**
     * 在已通过能力过滤的 AgentTool 中做混合召回和完整 Schema 装箱。
     * schemaTokenBudget <= 0 表示不按 Token 限制，但仍遵守 maxTools。
     */
    public DiscoveryResult discover(Collection<AgentTool> tools, String query,
            Collection<String> allowedNames, int schemaTokenBudget, int maxTools) {
        List<AgentTool> candidates = distinct(tools);
        Set<String> allowed = normalizeNames(allowedNames);
        if (!allowed.isEmpty()) {
            candidates = candidates.stream().filter(tool -> allowed.contains(tool.getName())).toList();
        }
        RankedCandidates ranking = rankAgentTools(candidates, query);
        List<Candidate> ranked = ranking.candidates();
        List<Candidate> selected = pack(ranked, schemaTokenBudget,
                maxTools <= 0 ? properties.maxInitialTools() : maxTools);
        return result(ranked, selected, ranking.semanticUsed(), false);
    }

    /**
     * 对 AgentScope 当前模型输入中的 Schema 做同样的混合排序。这里只能收窄发送视图，
     * 不修改 Toolkit 注册表；执行期由已有 middleware/ToolGroup 再做一次交集门禁。
     */
    public SchemaSelection selectSchemas(Collection<ToolSchema> schemas, String query,
            int schemaTokenBudget) {
        if (schemas == null || schemas.isEmpty()) {
            return new SchemaSelection(List.of(), Set.of(), false, 0);
        }
        List<SchemaCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (ToolSchema schema : schemas) {
            if (schema != null && schema.getName() != null && !schema.getName().isBlank()) {
                candidates.add(new SchemaCandidate(index++, schema));
            }
        }
        if (schemaTokenBudget <= 0) {
            return new SchemaSelection(List.of(), schemaNames(candidates), false, 0);
        }
        RankedSchemas ranking = rankSchemas(candidates, query);
        List<SchemaRank> ranked = ranking.schemas();
        SchemaRank searchTool = ranked.stream()
                .filter(candidate -> SEARCH_TOOLS_NAME.equals(candidate.schema().getName()))
                .findFirst().orElse(null);
        List<SchemaRank> ordinary = searchTool == null ? ranked
                : ranked.stream().filter(candidate -> candidate != searchTool).toList();
        boolean reserveSearchTool = fitsReservedSchema(schemaTokenBudget, searchTool);
        int ordinaryBudget = reserveSearchTool
                ? schemaTokenBudget - searchTool.schemaTokens() : schemaTokenBudget;
        List<SchemaRank> selected = new ArrayList<>(ordinaryBudget == 0 ? List.of()
                : packSchemas(ordinary, ordinaryBudget, properties.maxInitialTools()));
        if (reserveSearchTool) {
            selected.add(searchTool);
        }
        Set<String> omitted = new LinkedHashSet<>();
        for (SchemaRank candidate : ranked) {
            if (!selected.contains(candidate)) {
                omitted.add(candidate.schema().getName());
            }
        }
        List<ToolSchema> ordered = selected.stream().sorted(Comparator.comparingInt(value -> value.source().index()))
                .map(SchemaRank::schema).toList();
        return new SchemaSelection(ordered, Set.copyOf(omitted), ranking.semanticUsed(),
                estimateSchemas(ordered));
    }

    public int estimateSchemaTokens(ToolSchema schema) {
        return schema == null ? 0 : estimate(List.of(safe(schema::getName), safe(schema::getDescription),
                String.valueOf(schema.getParameters()), String.valueOf(schema.getOutputSchema())));
    }

    /** 当前配置是否允许向模型提供渐进发现入口。 */
    public boolean searchEnabled() {
        return properties.enabledValue() && properties.maxSearchExpansions() > 0;
    }

    public boolean enabledValue() {
        return properties.enabledValue();
    }

    private DiscoveryResult result(List<Candidate> ranked, List<Candidate> selected,
            boolean semanticUsed, boolean expansion) {
        Set<String> selectedNames = selected.stream().map(candidate -> candidate.tool().getName())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> omitted = ranked.stream().map(candidate -> candidate.tool().getName())
                .filter(name -> !selectedNames.contains(name))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<RankedTool> views = ranked.stream().map(candidate -> new RankedTool(candidate.tool(),
                candidate.score(), candidate.lexicalScore(), candidate.semanticScore(), candidate.schemaTokens(),
                selectedNames.contains(candidate.tool().getName()), candidate.index())).toList();
        int tokens = selected.stream().mapToInt(Candidate::schemaTokens).sum();
        return new DiscoveryResult(List.copyOf(views),
                selected.stream().map(Candidate::tool).toList(), Set.copyOf(omitted),
                semanticUsed, tokens, expansion);
    }

    private RankedCandidates rankAgentTools(List<AgentTool> tools, String query) {
        String normalizedQuery = normalize(query);
        float[] queryVector = semanticVector(normalizedQuery);
        boolean semanticUsed = false;
        List<Candidate> result = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            AgentTool tool = tools.get(index);
            String name = safe(tool::getName);
            String description = safe(tool::getDescription);
            String searchable = normalize(name + " " + description);
            double lexical = lexicalScore(normalizedQuery, name, searchable);
            float[] candidateVector = queryVector == null ? null : semanticVectorCached(name, description);
            boolean semanticAvailable = compatibleVectors(queryVector, candidateVector);
            double semanticScore = semanticAvailable ? cosine(queryVector, candidateVector) : 0d;
            double score = combine(lexical, semanticScore, semanticAvailable);
            semanticUsed |= semanticAvailable;
            result.add(new Candidate(index, tool, score, lexical, semanticScore,
                    estimateToolTokens(tool)));
        }
        result.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparingInt(Candidate::schemaTokens)
                .thenComparingInt(Candidate::index));
        return new RankedCandidates(List.copyOf(result), semanticUsed);
    }

    private RankedSchemas rankSchemas(List<SchemaCandidate> sources, String query) {
        String normalizedQuery = normalize(query);
        float[] queryVector = semanticVector(normalizedQuery);
        boolean semanticUsed = false;
        List<SchemaRank> result = new ArrayList<>();
        for (SchemaCandidate source : sources) {
            ToolSchema schema = source.schema();
            String name = safe(schema::getName);
            String description = safe(schema::getDescription);
            String searchable = normalize(name + " " + description);
            double lexical = lexicalScore(normalizedQuery, name, searchable);
            float[] candidateVector = queryVector == null ? null : semanticVectorCached(name, description);
            boolean semanticAvailable = compatibleVectors(queryVector, candidateVector);
            double semanticScore = semanticAvailable ? cosine(queryVector, candidateVector) : 0d;
            result.add(new SchemaRank(source, combine(lexical, semanticScore, semanticAvailable), lexical,
                    semanticScore, estimateSchemaTokens(schema)));
            semanticUsed |= semanticAvailable;
        }
        result.sort(Comparator.comparingDouble(SchemaRank::score).reversed()
                .thenComparingInt(SchemaRank::schemaTokens)
                .thenComparingInt(value -> value.source().index()));
        return new RankedSchemas(List.copyOf(result), semanticUsed);
    }

    private static boolean compatibleVectors(float[] query, float[] candidate) {
        return query != null && candidate != null && query.length == candidate.length;
    }

    private static boolean fitsReservedSchema(int budget, SchemaRank reserved) {
        return reserved != null && reserved.schemaTokens() <= budget;
    }

    private static Set<String> schemaNames(Collection<SchemaCandidate> candidates) {
        Set<String> names = new LinkedHashSet<>();
        for (SchemaCandidate candidate : candidates) {
            names.add(candidate.schema().getName());
        }
        return Set.copyOf(names);
    }

    private List<Candidate> pack(List<Candidate> ranked, int budget, int maxTools) {
        if (ranked.isEmpty() || maxTools <= 0) {
            return List.of();
        }
        int remaining = budget <= 0 ? Integer.MAX_VALUE : budget;
        boolean hasRelevant = ranked.stream().anyMatch(value -> value.score() >= properties.minimumScore()
                && value.score() > 0d);
        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : ranked) {
            if (selected.size() >= maxTools || candidate.schemaTokens() > remaining) {
                continue;
            }
            if (hasRelevant && candidate.score() < properties.minimumScore()) {
                continue;
            }
            selected.add(candidate);
            remaining -= candidate.schemaTokens();
        }
        return selected;
    }

    private List<SchemaRank> packSchemas(List<SchemaRank> ranked, int budget, int maxTools) {
        if (ranked.isEmpty() || maxTools <= 0) {
            return List.of();
        }
        int remaining = budget <= 0 ? Integer.MAX_VALUE : budget;
        boolean hasRelevant = ranked.stream().anyMatch(value -> value.score() >= properties.minimumScore()
                && value.score() > 0d);
        List<SchemaRank> selected = new ArrayList<>();
        for (SchemaRank candidate : ranked) {
            if (selected.size() >= maxTools || candidate.schemaTokens() > remaining) {
                continue;
            }
            if (hasRelevant && candidate.score() < properties.minimumScore()) {
                continue;
            }
            selected.add(candidate);
            remaining -= candidate.schemaTokens();
        }
        return selected;
    }

    private double combine(double lexical, double semantic, boolean semanticAvailable) {
        if (!semanticAvailable) {
            return lexical;
        }
        return properties.normalizedLexicalWeight() * lexical
                + properties.normalizedSemanticWeight() * semantic;
    }

    private double lexicalScore(String query, String name, String searchable) {
        if (query.isBlank()) {
            return 0d;
        }
        double score = 0d;
        if (!name.isBlank() && query.contains(name)) {
            score += 0.70d;
        }
        Set<String> queryTokens = tokens(query);
        Set<String> searchableTokens = tokens(searchable);
        if (!queryTokens.isEmpty()) {
            long overlap = queryTokens.stream().filter(searchableTokens::contains).count();
            score += 0.25d * ((double) overlap / queryTokens.size());
        }
        int bigramMatches = 0;
        int bigrams = 0;
        for (int i = 0; i + 1 < query.length(); i++) {
            String gram = query.substring(i, i + 2);
            if (isCjk(gram.charAt(0)) && isCjk(gram.charAt(1))) {
                bigrams++;
                if (searchable.contains(gram)) {
                    bigramMatches++;
                }
            }
        }
        if (bigrams > 0) {
            score += 0.35d * ((double) bigramMatches / bigrams);
        }
        return Math.min(1d, score);
    }

    private float[] semanticVector(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return semanticVectorCached("query:" + text, text);
    }

    private float[] semanticVectorCached(String keyPart, String text) {
        String key = keyPart + "\u0000" + text;
        float[] cached = vectorCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            float[] value = embedding.embed(text);
            validateVector(value);
            if (vectorCache.size() >= 2_048) {
                // 目录是可重建缓存；简单淘汰一个条目即可避免无界增长。
                vectorCache.keySet().stream().findFirst().ifPresent(vectorCache::remove);
            }
            float[] copy = value.clone();
            vectorCache.putIfAbsent(key, copy);
            return copy;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void validateVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("empty embedding");
        }
        double norm = 0d;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("invalid embedding");
            }
            norm += value * value;
        }
        if (norm == 0d) {
            throw new IllegalArgumentException("zero embedding");
        }
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0d;
        }
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0d || rightNorm == 0d) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String token : value.split("[^\\p{L}\\p{N}_]+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private static boolean isCjk(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static int estimateToolTokens(AgentTool tool) {
        if (tool == null) {
            return 0;
        }
        return estimate(List.of(safe(tool::getName), safe(tool::getDescription),
                String.valueOf(tool.getParameters()), String.valueOf(tool.getOutputSchema())));
    }

    private static int estimateSchemas(Collection<ToolSchema> schemas) {
        int total = 0;
        if (schemas != null) {
            for (ToolSchema schema : schemas) {
                if (schema != null) {
                    total = saturatedAdd(total, estimate(List.of(safe(schema::getName), safe(schema::getDescription),
                            String.valueOf(schema.getParameters()), String.valueOf(schema.getOutputSchema()))));
                }
            }
        }
        return total;
    }

    private static int estimate(Collection<String> values) {
        int total = 0;
        for (String value : values) {
            total = saturatedAdd(total, TokenEstimator.estimate(value));
        }
        return total;
    }

    private static int saturatedAdd(int left, int right) {
        long total = (long) Math.max(0, left) + Math.max(0, right);
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static List<AgentTool> distinct(AgentTool[] tools) {
        if (tools == null || tools.length == 0) {
            return List.of();
        }
        return distinct(Arrays.asList(tools));
    }

    private static List<AgentTool> distinct(Collection<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (tool == null) {
                continue;
            }
            String name = safe(tool::getName);
            if (name.isBlank()) {
                continue;
            }
            byName.putIfAbsent(name, tool);
        }
        return List.copyOf(byName.values());
    }

    private static Set<String> normalizeNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim());
            }
        }
        return Set.copyOf(result);
    }

    private static String safe(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private record Candidate(int index, AgentTool tool, double score, double lexicalScore,
            double semanticScore, int schemaTokens) {
    }

    /** 排序快照同时携带本次查询是否确实生成了可用语义向量。 */
    private record RankedCandidates(List<Candidate> candidates, boolean semanticUsed) {
    }

    private record SchemaCandidate(int index, ToolSchema schema) {
    }

    private record SchemaRank(SchemaCandidate source, double score, double lexicalScore,
            double semanticScore, int schemaTokens) {
        private ToolSchema schema() {
            return source.schema();
        }
    }

    /** Schema 排序快照同时携带本次查询是否确实生成了可用语义向量。 */
    private record RankedSchemas(List<SchemaRank> schemas, boolean semanticUsed) {
    }

    /** 面向诊断/测试的候选排序快照；不包含用户请求原文。 */
    public record RankedTool(AgentTool tool, double score, double lexicalScore, double semanticScore,
            int estimatedSchemaTokens, boolean selected, int originalIndex) {
        public String name() {
            return tool == null ? "" : tool.getName();
        }
    }

    public record DiscoveryResult(List<RankedTool> ranked, List<AgentTool> selected,
            Set<String> omittedToolNames, boolean semanticUsed, int estimatedSchemaTokens,
            boolean expansion) {
        public DiscoveryResult {
            ranked = ranked == null ? List.of() : List.copyOf(ranked);
            selected = selected == null ? List.of() : List.copyOf(selected);
            omittedToolNames = omittedToolNames == null ? Set.of() : Set.copyOf(omittedToolNames);
            estimatedSchemaTokens = Math.max(0, estimatedSchemaTokens);
        }

        public Set<String> selectedToolNames() {
            return selected.stream().map(AgentTool::getName).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public record SchemaSelection(List<ToolSchema> selected, Set<String> omittedToolNames,
            boolean semanticUsed, int estimatedSchemaTokens) {
        public SchemaSelection {
            selected = selected == null ? List.of() : List.copyOf(selected);
            omittedToolNames = omittedToolNames == null ? Set.of() : Set.copyOf(omittedToolNames);
            estimatedSchemaTokens = Math.max(0, estimatedSchemaTokens);
        }
    }

    /**
     * 请求级发现状态。它只持有调用方已经通过硬过滤的 AgentTool，不跨请求共享授权集合。
     */
    public final class DiscoverySession {
        private final List<AgentTool> candidates;
        private final String initialQuery;
        private final Set<String> loadedNames = ConcurrentHashMap.newKeySet();
        private final AtomicInteger expansions = new AtomicInteger();
        private volatile ToolLoader loader = ignored -> { };
        private volatile DiscoveryResult initialResult;
        private volatile boolean searchToolAvailable;

        private DiscoverySession(List<AgentTool> candidates, String initialQuery) {
            this.candidates = List.copyOf(candidates);
            this.initialQuery = boundedQuery(initialQuery);
        }

        public synchronized DiscoveryResult initial() {
            if (initialResult != null) {
                return initialResult;
            }
            int budget = properties.maxInitialSchemaTokens();
            DiscoveryResult result = discover(candidates, initialQuery, Set.of(),
                    budget, properties.maxInitialTools());
            if (searchEnabled() && !result.omittedToolNames().isEmpty()) {
                int ordinaryBudget = reserveSearchToolTokens(budget);
                if (ordinaryBudget >= 0) {
                    result = discoverWithinCapacity(candidates, initialQuery, ordinaryBudget,
                            properties.maxInitialTools(), false);
                    searchToolAvailable = true;
                }
            }
            loadedNames.addAll(result.selectedToolNames());
            initialResult = result;
            return initialResult;
        }

        public boolean hasMore() {
            return candidates.stream().anyMatch(tool -> !loadedNames.contains(tool.getName()));
        }

        public int expansionsUsed() {
            return expansions.get();
        }

        public int maxExpansions() {
            return properties.maxSearchExpansions();
        }

        /**
         * 将后续选中的工具挂载到调用方维护的基线 ToolGroup。
         *
         * <p>加载器可覆盖 {@link ToolLoader#rollback(List)}。发现结果只有在 load
         * 成功后才会推进 loadedNames；load 部分成功后抛错时，框架会调用 rollback，
         * 从而避免“模型看见了半组工具”的状态。</p>
         */
        public void bindLoader(ToolLoader loader) {
            this.loader = loader == null ? ignored -> { } : loader;
        }

        /** 返回给模型的固定 MetaTool；查询结果只包含脱敏描述和稳定工具名。 */
        public AgentTool searchTool() {
            if (!searchEnabled() || !hasMore()
                    || (initialResult != null && !searchToolAvailable)
                    || (initialResult == null && reserveSearchToolTokens(properties.maxInitialSchemaTokens()) < 0)) {
                return null;
            }
            return new SearchTool(this);
        }

        private synchronized ToolResultBlock search(Map<String, Object> input) {
            String query = boundedQuery(input == null ? null : String.valueOf(input.getOrDefault("query", "")));
            if (query.isBlank()) {
                return ToolResultBlock.error("工具发现查询不能为空，请用一句话描述需要的能力。 ");
            }
            if (expansions.get() >= properties.maxSearchExpansions()) {
                return ToolResultBlock.error("本轮工具发现次数已用尽（最多 "
                        + properties.maxSearchExpansions() + " 次），请基于当前工具结果继续。 ");
            }
            int ordinaryBudget = reserveSearchToolTokens(properties.maxSearchSchemaTokens());
            if (ordinaryBudget < 0) {
                return ToolResultBlock.error("工具发现结果超出本轮 Schema 预算，请基于当前工具继续或缩小请求。 ");
            }
            // 预算先于次数扣减：预算不足只是一次未执行的尝试，不应消耗扩展配额。
            expansions.incrementAndGet();
            DiscoveryResult result = discoverWithinCapacity(candidates.stream()
                            .filter(tool -> !loadedNames.contains(tool.getName())).toList(),
                    query, ordinaryBudget, properties.maxSearchResults(), true);
            if (result.selected().isEmpty()) {
                return ToolResultBlock.text("没有找到与该描述匹配且已获授权的工具；请换一种说法或直接向用户说明无法完成。 ");
            }
            try {
                loader.load(result.selected());
                loadedNames.addAll(result.selectedToolNames());
            } catch (RuntimeException e) {
                try {
                    loader.rollback(result.selected());
                } catch (RuntimeException ignored) {
                    // 回滚是尽力而为；原始挂载失败仍以稳定错误返回给模型。
                }
                return ToolResultBlock.error("工具发现结果挂载失败，本轮继续使用原工具集合。 ");
            }
            StringBuilder output = new StringBuilder("已发现并挂载以下工具（仅表示能力，不代表资源权限已通过）：\n");
            for (AgentTool tool : result.selected()) {
                if (output.length() >= MAX_RESULT_CHARS) {
                    break;
                }
                String description = safe(tool::getDescription).replaceAll("\\s+", " ").trim();
                if (description.length() > MAX_DESCRIPTION_CHARS) {
                    description = description.substring(0, MAX_DESCRIPTION_CHARS) + "...";
                }
                output.append("- ").append(tool.getName()).append(": ").append(description).append('\n');
            }
            return ToolResultBlock.text(output.toString());
        }

        private String boundedQuery(String value) {
            String normalized = value == null ? "" : value.trim();
            return normalized.length() <= MAX_QUERY_CHARS
                    ? normalized : normalized.substring(0, MAX_QUERY_CHARS);
        }

        private int reserveSearchToolTokens(int budget) {
            AgentTool metaTool = new SearchTool(this);
            return budget - estimateToolTokens(metaTool);
        }
    }

    /**
     * 请求级工具加载事务。{@link #load(List)} 可以在中途失败；此时调用方必须把
     * 传入批次视为“可能部分落地”，并在 {@link #rollback(List)} 中做幂等清理。
     */
    @FunctionalInterface
    public interface ToolLoader {
        void load(List<AgentTool> tools);

        /** 默认无状态加载器无需回滚；动态 Toolkit 加载器应覆盖此方法。 */
        default void rollback(List<AgentTool> tools) {
        }
    }

    /** 内部预算路径将零视为“没有可给普通工具的容量”，与公开 discover 的无上限语义分开。 */
    private DiscoveryResult discoverWithinCapacity(Collection<AgentTool> tools, String query,
            int schemaTokenBudget, int maxTools, boolean expansion) {
        RankedCandidates ranking = rankAgentTools(distinct(tools), query);
        List<Candidate> selected = schemaTokenBudget <= 0 ? List.of()
                : pack(ranking.candidates(), schemaTokenBudget, maxTools);
        return result(ranking.candidates(), selected, ranking.semanticUsed(), expansion);
    }

    private static final class SearchTool implements AgentTool {
        private final DiscoverySession session;

        private SearchTool(DiscoverySession session) {
            this.session = session;
        }

        @Override
        public String getName() {
            return SEARCH_TOOLS_NAME;
        }

        @Override
        public String getDescription() {
            return "在当前请求已获授权的工具目录中按自然语言查找额外能力。"
                    + "仅在现有工具无法完成目标时调用；每轮最多扩展一次。"
                    + "返回的是能力描述，不代表文件、邮箱等具体资源已经授权。";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of("query", Map.of(
                    "type", "string", "description", "要查找的能力，例如：把会议整理成日历")),
                    "required", List.of("query"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromSupplier(() -> session.search(param == null ? Map.of() : param.getInput()));
        }
    }
}
