package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KnowledgeRetrievalQualityGateTest {

    private static final KnowledgeRetrievalQualityGate.Thresholds THRESHOLDS =
            new KnowledgeRetrievalQualityGate.Thresholds(1, 0.8, 0.8, 0, 0, 0, 0);

    @Test
    void passesReviewableResultsAndExplicitNoResultCase() {
        List<KnowledgeRetrievalQualityGate.CaseDefinition> definitions = List.of(
                new KnowledgeRetrievalQualityGate.CaseDefinition("policy", "报销规则",
                        List.of("docs/policy.md"), List.of(), "pgvector-v1", Map.of("docs/policy.md", 2), false),
                new KnowledgeRetrievalQualityGate.CaseDefinition("empty", "不存在",
                        List.of(), List.of(), "pgvector-v1", Map.of(), true));
        Map<String, List<KnowledgeRetrievalQualityGate.Candidate>> candidates = Map.of(
                "policy", List.of(candidate("docs/policy.md", "policy-v2", 2, "报销审批规则原文已经通过人工核对", "章节：审批")),
                "empty", List.of());

        KnowledgeRetrievalQualityGate.Report report = KnowledgeRetrievalQualityGate.evaluate(
                definitions, candidates, THRESHOLDS);

        assertThat(report.status()).isEqualTo(KnowledgeRetrievalQualityGate.GateStatus.PASS);
        assertThat(report.averageRecallAtK()).isEqualTo(1.0);
        assertThat(report.averageCitationAccuracy()).isEqualTo(1.0);
        assertThat(report.cases()).allSatisfy(item ->
                assertThat(item.status()).isEqualTo(KnowledgeRetrievalQualityGate.GateStatus.PASS));
    }

    @Test
    void failsUnauthorizedStaleDuplicateAndLowQualityCandidates() {
        KnowledgeRetrievalQualityGate.CaseDefinition definition =
                new KnowledgeRetrievalQualityGate.CaseDefinition("defects", "安全规范",
                        List.of("docs/security.md"), List.of("docs/private.md"), "pgvector-v1",
                        Map.of("docs/security.md", 3), false);
        List<KnowledgeRetrievalQualityGate.Candidate> candidates = List.of(
                candidate("docs/private.md", "hash-private", 3, "不应出现的私有安全制度完整内容", "章节：私有"),
                candidate("docs/security.md", "same-hash", 2, "过期的安全规范完整内容已经失效", "章节：安全"),
                candidate("docs/security-copy.md", "same-hash", 3, "短", ""));

        KnowledgeRetrievalQualityGate.CaseResult result = KnowledgeRetrievalQualityGate.evaluate(
                List.of(definition), Map.of("defects", candidates), THRESHOLDS).cases().get(0);

        assertThat(result.status()).isEqualTo(KnowledgeRetrievalQualityGate.GateStatus.FAIL);
        assertThat(result.unauthorizedCount()).isEqualTo(1);
        assertThat(result.staleCount()).isEqualTo(1);
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.lowQualityCount()).isEqualTo(1);
        assertThat(result.failureTypes()).contains("UNAUTHORIZED", "STALE_DOCUMENT", "DUPLICATE", "LOW_QUALITY");
    }

    @Test
    void pendingWhenEvaluationSetHasNoSamples() {
        KnowledgeRetrievalQualityGate.Report report = KnowledgeRetrievalQualityGate.evaluate(
                List.of(), Map.of(), new KnowledgeRetrievalQualityGate.Thresholds(1, 0.8, 0.8, 0, 0, 0, 0));

        assertThat(report.status()).isEqualTo(KnowledgeRetrievalQualityGate.GateStatus.PENDING);
        assertThat(report.reasons()).contains("评测样本不足：0/1");
    }

    @Test
    void datasetCoversRequired27dFailureModes() {
        assertThat(KnowledgeRetrievalEvaluationDataset.cases()).hasSize(6)
                .extracting(KnowledgeRetrievalQualityGate.CaseDefinition::id)
                .containsExactly("recall-policy", "citation-location", "permission-boundary",
                        "stale-document", "duplicate-file", "no-result");
    }

    private static KnowledgeRetrievalQualityGate.Candidate candidate(String path, String fingerprint,
            int version, String quote, String location) {
        return new KnowledgeRetrievalQualityGate.Candidate(path, fingerprint, version, "pgvector-v1",
                0.91, quote, location, KnowledgeRetrievalQualityGate.OWNER_SCOPE);
    }
}
