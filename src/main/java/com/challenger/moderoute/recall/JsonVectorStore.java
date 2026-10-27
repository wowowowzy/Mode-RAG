package com.challenger.moderoute.recall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 文件版向量存储：一份 JSON、进程内余弦。保留它是为了在拿不到 sqlite-vec 扩展的机器上
 * 仍能跑通全链路（以及让接口测试不依赖原生库）。
 */
public final class JsonVectorStore implements VectorStore {

    private record Snapshot(String libraryVersion, String model, List<String> cardIds, float[][] vectors) {
    }

    private final VectorCache cache;
    private volatile Snapshot snapshot;

    public JsonVectorStore(VectorCache cache) {
        this.cache = cache;
    }

    @Override
    public String kind() {
        return "json";
    }

    @Override
    public boolean ready(String libraryVersion, String model, List<String> cardIds) {
        Optional<float[][]> loaded = cache.load(libraryVersion, model, cardIds);
        if (loaded.isEmpty()) {
            snapshot = null;
            return false;
        }
        snapshot = new Snapshot(libraryVersion, model, cardIds, loaded.get());
        return true;
    }

    @Override
    public List<ScoredCard> topK(String libraryVersion, String model, float[] query, int limit) {
        Snapshot current = snapshot;
        if (current == null || !current.libraryVersion().equals(libraryVersion) || !current.model().equals(model)) {
            throw new IllegalStateException("向量未就绪");
        }
        if (current.vectors().length == 0 || current.vectors()[0].length != query.length) {
            throw new IllegalStateException("查询维度与向量维度不一致");
        }
        List<ScoredCard> scored = new ArrayList<>(current.cardIds().size());
        for (int i = 0; i < current.cardIds().size(); i++) {
            scored.add(new ScoredCard(current.cardIds().get(i), round6(cosine(query, current.vectors()[i]))));
        }
        scored.sort(Comparator.comparingDouble(ScoredCard::score).reversed()
                .thenComparing(ScoredCard::cardId));
        return List.copyOf(scored.subList(0, Math.min(limit, scored.size())));
    }

    @Override
    public void save(String libraryVersion, String model, List<String> cardIds, List<float[]> vectors) {
        cache.save(libraryVersion, model, cardIds, vectors.toArray(new float[0][]));
        snapshot = new Snapshot(libraryVersion, model, cardIds, vectors.toArray(new float[0][]));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }
}
