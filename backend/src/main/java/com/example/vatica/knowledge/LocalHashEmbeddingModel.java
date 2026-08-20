package com.example.vatica.knowledge;

import java.text.Normalizer;
import java.util.Locale;

/** 零外部依赖 embedding gateway：以字符 unigram/bigram 生成稳定哈希向量。 */
public final class LocalHashEmbeddingModel implements EmbeddingGateway {

    private final int dimensions;

    public LocalHashEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        float[] vector = new float[dimensions];
        int[] codePoints = normalized.codePoints().toArray();
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

    @Override
    public int dimensions() {
        return dimensions;
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
