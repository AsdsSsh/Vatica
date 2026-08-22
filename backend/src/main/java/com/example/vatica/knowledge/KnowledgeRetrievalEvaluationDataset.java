package com.example.vatica.knowledge;

import java.util.List;
import java.util.Map;

/** 迭代 27D：可人工审查的最小知识检索评测集定义。 */
public final class KnowledgeRetrievalEvaluationDataset {

    private KnowledgeRetrievalEvaluationDataset() {
    }

    public static List<KnowledgeRetrievalQualityGate.CaseDefinition> cases() {
        return List.of(
                new KnowledgeRetrievalQualityGate.CaseDefinition("recall-policy", "报销审批规则是什么",
                        List.of("docs/policy.md"), List.of(), "pgvector-v1",
                        Map.of("docs/policy.md", 2), false),
                new KnowledgeRetrievalQualityGate.CaseDefinition("citation-location", "会议材料在哪里",
                        List.of("docs/meeting.md"), List.of(), "pgvector-v1", Map.of(), false),
                new KnowledgeRetrievalQualityGate.CaseDefinition("permission-boundary", "读取其他用户的私有制度",
                        List.of("docs/shared-policy.md"), List.of("docs/private-other-user.md"), "pgvector-v1",
                        Map.of(), false),
                new KnowledgeRetrievalQualityGate.CaseDefinition("stale-document", "最新员工手册",
                        List.of("docs/handbook.md"), List.of(), "pgvector-v1",
                        Map.of("docs/handbook.md", 3), false),
                new KnowledgeRetrievalQualityGate.CaseDefinition("duplicate-file", "检索安全规范",
                        List.of("docs/security.md"), List.of(), "pgvector-v1", Map.of(), false),
                new KnowledgeRetrievalQualityGate.CaseDefinition("no-result", "不存在的制度编号",
                        List.of(), List.of(), "pgvector-v1", Map.of(), true));
    }
}
