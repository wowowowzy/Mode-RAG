package com.challenger.moderoute.recall;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线占位向量：字符二元组的特征哈希。
 * 它没有语义能力，只保证零外部依赖下整条链路可跑、可测、可复现。
 */
public final class MockEmbeddingModel implements EmbeddingModel {

    private final int dimension;

    public MockEmbeddingModel(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> inputs = request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            embeddings.add(new Embedding(vectorize(inputs.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorize(document.getText());
    }

    @Override
    public int dimensions() {
        return dimension;
    }

    private float[] vectorize(String text) {
        float[] vector = new float[dimension];
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            vector[0] = 1f;
            return vector;
        }
        for (int i = 0; i < normalized.length(); i++) {
            String gram = i + 1 < normalized.length() ? normalized.substring(i, i + 2) : String.valueOf(normalized.charAt(i));
            int bucket = Math.floorMod(gram.hashCode(), dimension);
            vector[bucket] += 1f;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        double len = Math.sqrt(norm);
        if (len > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / len);
            }
        }
        return vector;
    }

    public String name() {
        return "mock-" + dimension;
    }
}
