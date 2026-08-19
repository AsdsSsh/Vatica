package com.example.vatica.knowledge;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 零外部依赖的 Spring AI EmbeddingModel：以字符 unigram/bigram 做稳定哈希向量。
 * 仅用于本地闭环、权限和生命周期测试；生产语义检索应替换为真实 embedding 模型。
 */
public final class LocalHashEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    public LocalHashEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> results = new ArrayList<>();
        List<String> inputs = request == null || request.getInstructions() == null
                ? List.of() : request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            results.add(new Embedding(hash(inputs.get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return hash(document == null ? "" : document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] hash(String source) {
        String text = Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        float[] vector = new float[dimensions];
        int[] codePoints = text.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            add(vector, codePoints[i], 1f);
            if (i + 1 < codePoints.length) {
                add(vector, 31L * codePoints[i] + codePoints[i + 1], 1.5f);
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            vector[0] = 1f;
            return vector;
        }
        float scale = (float) (1d / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    private void add(float[] vector, long token, float weight) {
        long mixed = token;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        int index = Math.floorMod((int) mixed, dimensions);
        vector[index] += (mixed & 1L) == 0 ? weight : -weight;
    }
}
