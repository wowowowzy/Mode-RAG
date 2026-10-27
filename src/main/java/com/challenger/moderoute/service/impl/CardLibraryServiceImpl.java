package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.client.EmbeddingBackend;
import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.mapper.CardLoader;
import com.challenger.moderoute.mapper.VectorMapper;
import com.challenger.moderoute.service.CardLibraryService;

import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.service.CardLibraryService;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.client.EmbeddingBackend;
import com.challenger.moderoute.mapper.VectorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动期加载卡片，按需（或预热）计算向量并写入 VectorMapper。
 * 库是只读快照；向量是派生数据，任何不匹配都整批重算。
 */
@Service
public class CardLibraryServiceImpl implements CardLibraryService {

    private static final Logger log = LoggerFactory.getLogger(CardLibraryServiceImpl.class);

    private final EmbeddingBackend embeddingBackend;
    private final VectorMapper vectorStore;
    private final boolean stripNegativeClause;
    private final int batchSize;

    private final CardLibrary library;
    private volatile boolean vectorsReady;

    public CardLibraryServiceImpl(ModeRouteProperties properties,
                              EmbeddingBackend embeddingBackend,
                              VectorMapper vectorStore) {
        Path cardsDir = Path.of(properties.getCardsDir()).toAbsolutePath().normalize();
        this.embeddingBackend = embeddingBackend;
        this.vectorStore = vectorStore;
        this.stripNegativeClause = properties.getRecall().isStripNegativeClause();
        this.batchSize = Math.max(1, Math.min(properties.getRecall().getBatchSize(), 20));

        List<Card> cards = CardLoader.load(cardsDir);
        String version = LibraryVersion.compute(cardsDir);
        this.library = CardLibrary.of(version, cards);
        log.info("模式库已加载: {} 张卡, 版本 {}, 向量存储 {}", cards.size(), version, vectorStore.kind());
    }

    public CardLibrary library() {
        return library;
    }

    public String embeddingModelName() {
        return embeddingBackend.name();
    }

    public String vectorStoreKind() {
        return vectorStore.kind();
    }

    public boolean vectorsReady() {
        return vectorsReady;
    }

    /** 首次请求或预热线程调用；失败抛出，由调用方降级。 */
    public CardLibrary ensureVectors() {
        if (vectorsReady) {
            return library;
        }
        synchronized (this) {
            if (vectorsReady) {
                return library;
            }
            List<String> cardIds = library.cardIds();
            if (vectorStore.ready(library.version(), embeddingBackend.name(), cardIds)) {
                vectorsReady = true;
                log.info("向量已就绪（存储命中）: {} 张卡, {}", cardIds.size(), vectorStore.kind());
                return library;
            }

            List<String> texts = library.cards().stream()
                    .map(card -> card.recallText(stripNegativeClause))
                    .toList();
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                vectors.addAll(embeddingBackend.model().embed(texts.subList(start, end)));
            }
            if (vectors.size() != texts.size()) {
                throw new IllegalStateException("向量数量与卡片数量不一致: " + vectors.size() + " vs " + texts.size());
            }
            int dimension = -1;
            for (float[] vector : vectors) {
                if (vector == null || vector.length == 0) {
                    throw new IllegalStateException("存在空向量");
                }
                if (dimension < 0) {
                    dimension = vector.length;
                } else if (dimension != vector.length) {
                    throw new IllegalStateException("向量维度不一致: " + dimension + " vs " + vector.length);
                }
            }
            vectorStore.save(library.version(), embeddingBackend.name(), cardIds, vectors);
            vectorsReady = true;
            log.info("向量已计算并写入 {}: {} 张卡, {} 维, 模型 {}",
                    vectorStore.kind(), cardIds.size(), dimension, embeddingBackend.name());
            return library;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAsync() {
        Thread thread = new Thread(() -> {
            try {
                ensureVectors();
            } catch (RuntimeException e) {
                log.warn("向量预热失败，首次请求将重试并可能降级: {}", e.getMessage());
            }
        }, "moderoute-vector-warmup");
        thread.setDaemon(true);
        thread.start();
    }
}
