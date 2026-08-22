package com.example.vatica.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 迭代 27D：离线知识召回质量门禁。
 *
 * <p>评测输入是脱离生产数据库的固定候选引用，因此不会触发云端 DDL、Embedding
 * 调用或文档写入。规则同时检查相关性和引用可审查性，避免只看向量分数就放行越权、
 * 过期或重复资料。</p>
 */
public final class KnowledgeRetrievalQualityGate {

    public static final String OWNER_SCOPE = "CURRENT_USER_OWNER";
    public static final String SHARED_SCOPE = "ORGANIZATION_SHARED";

    private KnowledgeRetrievalQualityGate() {
    }

    public enum GateStatus {
        PENDING,
        PASS,
        FAIL
    }

    public record Thresholds(int minCases, double minRecallAtK, double minCitationAccuracy,
            int maxUnauthorized, int maxStale, int maxDuplicates, int maxLowQuality) {
        public Thresholds {
            if (minCases < 1) {
                minCases = 1;
            }
            if (minRecallAtK < 0 || minRecallAtK > 1) {
                minRecallAtK = 0.8;
            }
            if (minCitationAccuracy < 0 || minCitationAccuracy > 1) {
                minCitationAccuracy = 0.8;
            }
            maxUnauthorized = Math.max(0, maxUnauthorized);
            maxStale = Math.max(0, maxStale);
            maxDuplicates = Math.max(0, maxDuplicates);
            maxLowQuality = Math.max(0, maxLowQuality);
        }

        public static Thresholds defaults() {
            return new Thresholds(1, 0.8, 0.8, 0, 0, 0, 0);
        }
    }

    /** 固定评测题目；expectedSources 为空且 expectNoResult 为 true 表示明确无结果题。 */
    public record CaseDefinition(String id, String query, List<String> expectedSources,
            List<String> forbiddenSources, String expectedIndexVersion,
            Map<String, Integer> minimumDocumentVersions, boolean expectNoResult) {
        public CaseDefinition {
            id = id == null ? "" : id.trim();
            query = query == null ? "" : query.trim();
            expectedSources = copyList(expectedSources);
            forbiddenSources = copyList(forbiddenSources);
            expectedIndexVersion = expectedIndexVersion == null ? "" : expectedIndexVersion.trim();
            minimumDocumentVersions = minimumDocumentVersions == null
                    ? Map.of() : Collections.unmodifiableMap(new HashMap<>(minimumDocumentVersions));
        }

        public CaseDefinition(String id, String query, List<String> expectedSources,
                List<String> forbiddenSources, String expectedIndexVersion) {
            this(id, query, expectedSources, forbiddenSources, expectedIndexVersion, Map.of(), false);
        }
    }

    /** 脱敏候选引用；sourceFingerprint 用于发现同一内容的重复文件。 */
    public record Candidate(String sourcePath, String sourceFingerprint, int documentVersion,
            String indexVersion, double score, String quote, String sourceLocation, String accessScope) {
        public Candidate {
            sourcePath = sourcePath == null ? "" : sourcePath.trim().replace('\\', '/');
            sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint.trim();
            indexVersion = indexVersion == null ? "" : indexVersion.trim();
            quote = quote == null ? "" : quote.trim();
            sourceLocation = sourceLocation == null ? "" : sourceLocation.trim();
            accessScope = accessScope == null ? "" : accessScope.trim();
        }
    }

    public record CaseResult(String caseId, int candidateCount, int expectedHitCount,
            int relevantHits, Double recallAtK, Double reciprocalRank, Double citationAccuracy,
            int unauthorizedCount, int staleCount, int duplicateCount, int lowQualityCount,
            GateStatus status, List<String> failureTypes) {
        public CaseResult {
            failureTypes = copyList(failureTypes);
        }
    }

    public record Report(String generatedAt, Thresholds thresholds, GateStatus status,
            int totalCases, int passedCases, Double averageRecallAtK, Double meanReciprocalRank,
            Double averageCitationAccuracy, int unauthorizedCount, int staleCount,
            int duplicateCount, int lowQualityCount, List<String> reasons, List<CaseResult> cases) {
        public Report {
            reasons = copyList(reasons);
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public static Report evaluate(List<CaseDefinition> definitions,
            Map<String, List<Candidate>> candidatesByCase, Thresholds thresholds) {
        Thresholds effective = thresholds == null ? Thresholds.defaults() : thresholds;
        List<CaseDefinition> cases = definitions == null ? List.of() : definitions.stream()
                .filter(item -> item != null && !item.id().isBlank())
                .toList();
        Map<String, List<Candidate>> candidates = candidatesByCase == null ? Map.of() : candidatesByCase;
        List<CaseResult> results = cases.stream()
                .map(item -> evaluateCase(item, candidates.getOrDefault(item.id(), List.of()), effective))
                .toList();

        int passed = (int) results.stream().filter(item -> item.status() == GateStatus.PASS).count();
        int unauthorized = results.stream().mapToInt(CaseResult::unauthorizedCount).sum();
        int stale = results.stream().mapToInt(CaseResult::staleCount).sum();
        int duplicates = results.stream().mapToInt(CaseResult::duplicateCount).sum();
        int lowQuality = results.stream().mapToInt(CaseResult::lowQualityCount).sum();
        Double recall = average(results.stream().map(CaseResult::recallAtK).toList());
        Double mrr = average(results.stream().map(CaseResult::reciprocalRank).toList());
        Double citationAccuracy = average(results.stream().map(CaseResult::citationAccuracy).toList());
        List<String> reasons = new ArrayList<>();
        if (results.size() < effective.minCases()) {
            reasons.add("评测样本不足：" + results.size() + "/" + effective.minCases());
        }
        if (recall == null || recall < effective.minRecallAtK()) {
            reasons.add("平均 Recall@K 未达到 " + percent(effective.minRecallAtK()));
        }
        if (citationAccuracy == null || citationAccuracy < effective.minCitationAccuracy()) {
            reasons.add("平均引用准确率未达到 " + percent(effective.minCitationAccuracy()));
        }
        if (unauthorized > effective.maxUnauthorized()) {
            reasons.add("发现越权候选：" + unauthorized + " 条");
        }
        if (stale > effective.maxStale()) {
            reasons.add("发现过期候选：" + stale + " 条");
        }
        if (duplicates > effective.maxDuplicates()) {
            reasons.add("发现重复文件候选：" + duplicates + " 条");
        }
        if (lowQuality > effective.maxLowQuality()) {
            reasons.add("发现低质量引用：" + lowQuality + " 条");
        }
        GateStatus status = results.size() < effective.minCases()
                ? GateStatus.PENDING : reasons.isEmpty() ? GateStatus.PASS : GateStatus.FAIL;
        return new Report(Instant.now().toString(), effective, status, results.size(), passed,
                recall, mrr, citationAccuracy, unauthorized, stale, duplicates, lowQuality,
                reasons, results);
    }

    private static CaseResult evaluateCase(CaseDefinition definition, List<Candidate> rows,
            Thresholds thresholds) {
        List<Candidate> candidates = rows == null ? List.of() : rows.stream()
                .filter(item -> item != null).toList();
        Set<String> expected = new LinkedHashSet<>(definition.expectedSources());
        Set<String> forbidden = Set.copyOf(definition.forbiddenSources());
        Set<String> relevantSources = new LinkedHashSet<>();
        Set<String> failureTypes = new LinkedHashSet<>();
        Map<String, Integer> fingerprints = new HashMap<>();
        int unauthorized = 0;
        int stale = 0;
        int lowQuality = 0;
        int relevantHits = 0;
        int citationHits = 0;
        double reciprocalRank = 0;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            boolean relevant = expected.contains(candidate.sourcePath());
            boolean authorized = isAuthorized(candidate.accessScope()) && !forbidden.contains(candidate.sourcePath());
            boolean staleIndex = !definition.expectedIndexVersion().isBlank()
                    && !definition.expectedIndexVersion().equals(candidate.indexVersion());
            Integer minimumVersion = definition.minimumDocumentVersions().get(candidate.sourcePath());
            boolean staleDocument = minimumVersion != null && candidate.documentVersion() < minimumVersion;
            boolean staleCandidate = staleIndex || staleDocument;
            boolean lowQualityCandidate = candidate.quote().length() < 12 || candidate.sourceLocation().isBlank();
            if (!authorized) {
                unauthorized++;
                failureTypes.add("UNAUTHORIZED");
            }
            if (staleCandidate) {
                stale++;
                if (staleIndex) {
                    failureTypes.add("STALE_INDEX");
                }
                if (staleDocument) {
                    failureTypes.add("STALE_DOCUMENT");
                }
            }
            if (lowQualityCandidate) {
                lowQuality++;
                failureTypes.add("LOW_QUALITY");
            }
            String fingerprint = candidate.sourceFingerprint().isBlank()
                    ? candidate.sourcePath() : candidate.sourceFingerprint();
            if (!fingerprint.isBlank() && fingerprints.merge(fingerprint, 1, Integer::sum) > 1) {
                failureTypes.add("DUPLICATE");
            }
            if (relevant && authorized && !staleCandidate) {
                if (relevantSources.add(candidate.sourcePath())) {
                    relevantHits++;
                    if (reciprocalRank == 0) {
                        reciprocalRank = 1.0 / (index + 1);
                    }
                }
                if (!lowQualityCandidate) {
                    citationHits++;
                }
            }
        }
        boolean noResultPass = definition.expectNoResult() && candidates.isEmpty();
        if (definition.expectNoResult() && !candidates.isEmpty()) {
            failureTypes.add("UNEXPECTED_RESULT");
        }
        int expectedHitCount = expected.size();
        if ((!definition.expectNoResult() && relevantHits < expectedHitCount)
                || (definition.expectNoResult() && !noResultPass)) {
            failureTypes.add(candidates.isEmpty() ? "NO_RESULT" : "MISS");
        }
        Double recall = definition.expectNoResult()
                ? (noResultPass ? 1.0 : 0.0)
                : expectedHitCount == 0 ? 0.0 : (double) relevantHits / expectedHitCount;
        Double accuracy = candidates.isEmpty()
                ? (definition.expectNoResult() ? 1.0 : 0.0)
                : (double) citationHits / candidates.size();
        boolean structuralFailure = failureTypes.contains("MISS") || failureTypes.contains("NO_RESULT")
                || failureTypes.contains("UNEXPECTED_RESULT");
        boolean pass = !structuralFailure && recall >= thresholds.minRecallAtK()
                && accuracy >= thresholds.minCitationAccuracy()
                && unauthorized <= thresholds.maxUnauthorized() && stale <= thresholds.maxStale()
                && duplicateCount(fingerprints) <= thresholds.maxDuplicates()
                && lowQuality <= thresholds.maxLowQuality();
        return new CaseResult(definition.id(), candidates.size(), expectedHitCount, relevantHits,
                recall, reciprocalRank == 0 ? 0.0 : reciprocalRank, accuracy, unauthorized, stale,
                duplicateCount(fingerprints), lowQuality, pass ? GateStatus.PASS : GateStatus.FAIL,
                List.copyOf(failureTypes));
    }

    private static int duplicateCount(Map<String, Integer> fingerprints) {
        return fingerprints.values().stream().mapToInt(value -> Math.max(0, value - 1)).sum();
    }

    private static boolean isAuthorized(String accessScope) {
        return OWNER_SCOPE.equals(accessScope) || SHARED_SCOPE.equals(accessScope);
    }

    private static Double average(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static String percent(double value) {
        return Math.round(value * 1000.0) / 10.0 + "%";
    }

    private static <T> List<T> copyList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
