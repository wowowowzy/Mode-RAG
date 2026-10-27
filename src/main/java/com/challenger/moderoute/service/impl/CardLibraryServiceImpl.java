package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.client.EmbeddingBackend;
import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.mapper.CardLoader;
import com.challenger.moderoute.mapper.CardMapper;
import com.challenger.moderoute.mapper.VectorMapper;
import com.challenger.moderoute.service.CardLibraryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 启动期把卡片同步进 SQLite，再从库里读出不可变快照；向量按需计算并写入向量表。
 *
 * 同步规则：YAML 目录存在时以文件内容算出库版本，与库里已导入版本不同才整批替换；
 * 目录缺失但库里已有数据时，直接使用库里的版本（便于只带 .db 部署）。
 */
@Service
public class CardLibraryServiceImpl implements CardLibraryService {

    private static final Logger log = LoggerFactory.getLogger(CardLibraryServiceImpl.class);

    private final EmbeddingBackend embeddingBackend;
    private final VectorMapper vectorMapper;
    private final CardMapper cardMapper;
    private final boolean stripNegativeClause;
    private final int batchSize;

    private final CardLibrary library;
    private volatile boolean vectorsReady;

    public CardLibraryServiceImpl(ModeRouteProperties properties,
                                  EmbeddingBackend embeddingBackend,
                                  VectorMapper vectorMapper,
                                  CardMapper cardMapper) {
        Path cardsDir = Path.of(properties.getCardsDir()).toAbsolutePath().normalize();
        this.embeddingBackend = embeddingBackend;
        this.vectorMapper = vectorMapper;
        this.cardMapper = cardMapper;
        this.stripNegativeClause = properties.getRecall().isStripNegativeClause();
        this.batchSize = Math.max(1, Math.min(properties.getRecall().getBatchSize(), 20));

        String version = syncCards(cardsDir);
        List<Card> cards = cardMapper.loadAll();
        if (cards.isEmpty()) {
            throw new IllegalStateException("模式库为空：既没有可导入的卡片目录，库里也没有卡片");
        }
        this.library = CardLibrary.of(version, cards);
        log.info("模式库已加载: {} 张卡, 版本 {}, 卡片存储 sqlite, 向量存储 {}", cards.size(), version, vectorMapper.kind());
    }

    /** 返回当前库版本。 */
    private String syncCards(Path cardsDir) {
        Optional<String> stored = cardMapper.loadedVersion();
        if (!Files.isDirectory(cardsDir)) {
            if (stored.isPresent()) {
                log.warn("卡片目录不存在，直接使用库里已导入的版本: {}", stored.get());
                return stored.get();
            }
            throw new IllegalStateException("卡片目录不存在且库里没有卡片: " + cardsDir);
        }
        String version = LibraryVersion.compute(cardsDir);
        if (stored.filter(version::equals).isPresent()) {
            log.info("卡片库已是最新: 版本 {}", version);
            return version;
        }
        List<Card> parsed = CardLoader.load(cardsDir);
        cardMapper.replaceAll(version, parsed);
        log.info("卡片已从 YAML 导入 SQLite: {} 张, 版本 {}", parsed.size(), version);
        return version;
    }

    public CardLibrary library() {
        return library;
    }

    public String embeddingModelName() {
        return embeddingBackend.name();
    }

    public String vectorStoreKind() {
        return vectorMapper.kind();
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
            if (vectorMapper.ready(library.version(), embeddingBackend.name(), cardIds)) {
                vectorsReady = true;
                log.info("向量已就绪（存储命中）: {} 张卡, {}", cardIds.size(), vectorMapper.kind());
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
            vectorMapper.save(library.version(), embeddingBackend.name(), cardIds, vectors);
            vectorsReady = true;
            log.info("向量已计算并写入 {}: {} 张卡, {} 维, 模型 {}",
                    vectorMapper.kind(), cardIds.size(), dimension, embeddingBackend.name());
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
