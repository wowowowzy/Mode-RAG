package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.ScoredCard;

import java.util.List;
import java.util.Optional;


public interface CardLibraryService {

    CardLibrary library();

    String embeddingModelName();

    String vectorStoreKind();

    boolean vectorsReady();

    /** 首次请求或预热线程调用；失败抛出，由调用方降级。 */
    CardLibrary ensureVectors();

    /**
     * 内存向量快照，与 {@link #library()}.cardIds() 同序。
     * 未就绪时返回空——注意「未就绪」不等于「失败」，调用方应回落到向量存储。
     */
    Optional<List<float[]>> memoryVectors();

    /**
     * 在内存向量上做余弦检索，按分数降序、同分按 cardId 升序。
     * 未就绪返回空，调用方回落到 {@code VectorMapper.topK}。
     */
    Optional<List<ScoredCard>> topKInMemory(float[] query, int limit);
}
