package com.challenger.moderoute.recall;

import com.challenger.moderoute.card.CardLibrary;
import com.challenger.moderoute.card.CardLibraryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 粗筛：全库余弦相似度暴力扫描。48 张卡约几万次乘加，进程内几微秒，
 * 比任何外部向量库的往返都快一个数量级。
 */
@Service
public class RecallService {

    /** 精判最多 26 个单字母选项，其中一位留给 None。 */
    public static final int MAX_CARDS_FOR_DECISION = 25;

    private final CardLibraryService libraryService;
    private final EmbeddingBackend embeddingBackend;

    public RecallService(CardLibraryService libraryService, EmbeddingBackend embeddingBackend) {
        this.libraryService = libraryService;
        this.embeddingBackend = embeddingBackend;
    }

    public List<ScoredCard> recall(String task, int topK) {
        CardLibrary library = libraryService.ensureVectors();
        if (!library.vectorsReady()) {
            throw new RecallException("向量未就绪");
        }

        float[] query;
        try {
            query = embeddingBackend.model().embed(task);
        } catch (RuntimeException e) {
            throw new RecallException("任务向量化失败: " + e.getMessage(), e);
        }
        if (query == null || query.length != library.dimension()) {
            throw new RecallException("查询向量维度异常: "
                    + (query == null ? "null" : query.length) + " vs " + library.dimension());
        }

        int limit = Math.min(topK, MAX_CARDS_FOR_DECISION);
        List<ScoredCard> scored = new ArrayList<>(library.cards().size());
        for (int i = 0; i < library.cards().size(); i++) {
            double score = round6(cosine(query, library.vectors()[i]));
            scored.add(new ScoredCard(library.cards().get(i).id(), score));
        }
        scored.sort(Comparator.comparingDouble(ScoredCard::score).reversed()
                .thenComparing(ScoredCard::cardId));
        return List.copyOf(scored.subList(0, Math.min(limit, scored.size())));
    }

    static double cosine(float[] a, float[] b) {
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
