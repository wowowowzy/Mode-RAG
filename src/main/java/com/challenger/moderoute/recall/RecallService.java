package com.challenger.moderoute.recall;

import com.challenger.moderoute.card.CardLibrary;
import com.challenger.moderoute.card.CardLibraryService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 粗筛。检索交给 VectorStore（SQLite 里走 vec_distance_cosine，文件版走进程内余弦），
 * 这里只负责把任务向量化并做参数收口。
 */
@Service
public class RecallService {

    /** 精判最多 26 个单字母选项，其中一位留给 None。 */
    public static final int MAX_CARDS_FOR_DECISION = 25;

    private final CardLibraryService libraryService;
    private final EmbeddingBackend embeddingBackend;
    private final VectorStore vectorStore;

    public RecallService(CardLibraryService libraryService, EmbeddingBackend embeddingBackend, VectorStore vectorStore) {
        this.libraryService = libraryService;
        this.embeddingBackend = embeddingBackend;
        this.vectorStore = vectorStore;
    }

    public List<ScoredCard> recall(String task, int topK) {
        CardLibrary library = libraryService.ensureVectors();
        if (!libraryService.vectorsReady()) {
            throw new RecallException("向量未就绪");
        }

        float[] query;
        try {
            query = embeddingBackend.model().embed(task);
        } catch (RuntimeException e) {
            throw new RecallException("任务向量化失败: " + e.getMessage(), e);
        }
        if (query == null || query.length == 0) {
            throw new RecallException("任务向量为空");
        }

        int limit = Math.min(topK, MAX_CARDS_FOR_DECISION);
        try {
            List<ScoredCard> scored = vectorStore.topK(
                    library.version(), embeddingBackend.name(), query, limit);
            if (scored.isEmpty()) {
                throw new RecallException("向量存储未返回候选");
            }
            return scored;
        } catch (RuntimeException e) {
            throw new RecallException("向量检索失败: " + e.getMessage(), e);
        }
    }
}
