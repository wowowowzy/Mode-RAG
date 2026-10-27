package com.challenger.moderoute.card;

import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.recall.EmbeddingBackend;
import com.challenger.moderoute.recall.VectorCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 启动期加载卡片，按需（或预热）计算向量。库是只读快照，替换时整体原子替换。
 */
@Service
public class CardLibraryService {

    private static final Logger log = LoggerFactory.getLogger(CardLibraryService.class);

    private final Path cardsDir;
    private final EmbeddingBackend embeddingBackend;
    private final VectorCache cache;
    private final boolean stripNegativeClause;
    private final int batchSize;

    private volatile CardLibrary library;

    public CardLibraryService(ModeRouteProperties properties, EmbeddingBackend embeddingBackend, VectorCache cache) {
        this.cardsDir = Path.of(properties.getCardsDir()).toAbsolutePath().normalize();
        this.embeddingBackend = embeddingBackend;
        this.cache = cache;
        this.stripNegativeClause = properties.getRecall().isStripNegativeClause();
        this.batchSize = Math.max(1, Math.min(properties.getRecall().getBatchSize(), 20));

        List<Card> cards = CardLoader.load(cardsDir);
        String version = LibraryVersion.compute(cardsDir);
        this.library = CardLibrary.of(version, cards);
        log.info("模式库已加载: {} 张卡, 版本 {}", cards.size(), version);
    }

    public CardLibrary library() {
        return library;
    }

    /** 首次请求或预热线程调用；失败抛出，由调用方降级。 */
    public CardLibrary ensureVectors() {
        CardLibrary current = library;
        if (current.vectorsReady()) {
            return current;
        }
        synchronized (this) {
            current = library;
            if (current.vectorsReady()) {
                return current;
            }
            List<String> cardIds = current.cardIds();
            Optional<float[][]> cached = cache.load(current.version(), embeddingBackend.name(), cardIds);
            if (cached.isPresent()) {
                library = current.withVectors(cached.get(), embeddingBackend.name());
                log.info("向量缓存命中: {} 张卡, {} 维", cardIds.size(), library.dimension());
                return library;
            }

            List<String> texts = current.cards().stream()
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
            float[][] array = new float[vectors.size()][];
            int dimension = -1;
            for (int i = 0; i < vectors.size(); i++) {
                float[] vector = vectors.get(i);
                if (vector == null || vector.length == 0) {
                    throw new IllegalStateException("第 " + i + " 张卡的向量为空");
                }
                if (dimension < 0) {
                    dimension = vector.length;
                } else if (dimension != vector.length) {
                    throw new IllegalStateException("向量维度不一致: " + dimension + " vs " + vector.length);
                }
                array[i] = vector;
            }
            library = current.withVectors(array, embeddingBackend.name());
            cache.save(current.version(), embeddingBackend.name(), cardIds, array);
            log.info("向量已计算: {} 张卡, {} 维, 模型 {}", cardIds.size(), dimension, embeddingBackend.name());
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
