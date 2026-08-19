package com.example.vatica.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalHashEmbeddingModelTest {

    @Test
    void producesStableNormalizedVectorsAndRanksKeywordOverlap() {
        LocalHashEmbeddingModel model = new LocalHashEmbeddingModel(256);

        float[] query = model.embed("知识库权限隔离");
        float[] relevant = model.embed("知识库需要按组织和用户做权限隔离");
        float[] unrelated = model.embed("明天下午安排产品会议");

        assertThat(query).hasSize(256).containsExactly(model.embed("知识库权限隔离"));
        assertThat(norm(query)).isCloseTo(1d, org.assertj.core.data.Offset.offset(0.0001d));
        assertThat(cosine(query, relevant)).isGreaterThan(cosine(query, unrelated));
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += value * value;
        return Math.sqrt(sum);
    }

    private static double cosine(float[] left, float[] right) {
        double value = 0;
        for (int i = 0; i < left.length; i++) value += left[i] * right[i];
        return value;
    }
}
