package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.client.EmbeddingBackend;
import com.challenger.moderoute.common.util.CosineSimilarity;
import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.ScoredCard;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 启动期把卡片同步进 SQLite，再从库里读出不可变快照；向量按需计算。
 *
 * 向量有三条不变量：
 * 1. 计算只在内存里做一次，结果常驻 volatile 字段，与 cardIds 同序；
 * 2. 并发请求不排队——抢不到计算锁的请求立刻失败，由上层走规则降级；
 * 3. 持久化失败不影响当前进程，只影响「重启后要不要重算」。
 *
 * vectorsReady 的语义是「向量可用」（内存或存储），vectors != null 表示「内存有副本」。
 * 存储命中时不留内存副本，召回直接走 SQLite，省一次启动期读盘。
 */
@Service
public class CardLibraryServiceImpl implements CardLibraryService {

    private static final Logger log = LoggerFactory.getLogger(CardLibraryServiceImpl.class);

    /** 失败后的冷却窗口：期间直接快速失败，不再打网关。 */
    private static final long FAILURE_COOLDOWN_MS = 60_000L;

    private final EmbeddingBackend embeddingBackend;
    private final VectorMapper vectorMapper;
    private final CardMapper cardMapper;
    private final boolean stripNegativeClause;
    private final int batchSize;

    private final CardLibrary library;
    /** 与 library.cards() 同序（均为 cardId 升序），避免热路径反复生成。 */
    private final List<String> cardIds;

    /** 串行化「计算向量」这一个动作；tryLock 保证其余请求不排队。 */
    private final ReentrantLock computeLock = new ReentrantLock();

    /** 与 cardIds 同序；null 表示尚未计算过（可能是存储命中，也可能是还没跑）。 */
    private volatile List<float[]> vectors;
    private volatile boolean vectorsReady;
    private volatile long lastFailureAt;

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
        this.cardIds = this.library.cardIds();
        log.info("模式库已加载: {} 张卡, 版本 {}, 卡片存储 sqlite, 向量存储 {}",
                cards.size(), version, vectorMapper.kind());
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

    @Override
    public CardLibrary library() {
        return library;
    }

    @Override
    public String embeddingModelName() {
        return embeddingBackend.name();
    }

    @Override
    public String vectorStoreKind() {
        return vectorMapper.kind();
    }

    @Override
    public boolean vectorsReady() {
        return vectorsReady;
    }

    @Override
    public Optional<List<float[]>> memoryVectors() {
        List<float[]> current = vectors;
        return current == null ? Optional.empty() : Optional.of(current);
    }

    @Override
    public Optional<List<ScoredCard>> topKInMemory(float[] query, int limit) {
        List<float[]> current = vectors;
        if (current == null || query == null || query.length == 0) {
            return Optional.empty();
        }
        if (current.size() != cardIds.size()) {
            return Optional.empty();
        }
        if (limit <= 0) {
            return Optional.of(List.of());
        }
        List<ScoredCard> scored = new ArrayList<>(cardIds.size());
        for (int i = 0; i < cardIds.size(); i++) {
            scored.add(new ScoredCard(cardIds.get(i), round6(CosineSimilarity.of(query, current.get(i)))));
        }
        scored.sort(Comparator.comparingDouble(ScoredCard::score).reversed()
                .thenComparing(ScoredCard::cardId));
        return Optional.of(List.copyOf(scored.subList(0, Math.min(limit, scored.size()))));
    }

    /**
     * 首次请求或预热线程调用。
     *
     * 失败语义：抛 RuntimeException，由 EnhanceServiceImpl 转成 DEGRADED。
     * 并发语义：只有一个线程真正计算，其余立刻抛，不阻塞、不排队。
     */
    @Override
    public CardLibrary ensureVectors() {
        if (vectorsReady) {
            return library;
        }

        long failedAt = lastFailureAt;
        if (failedAt > 0) {
            long elapsed = System.currentTimeMillis() - failedAt;
            if (elapsed < FAILURE_COOLDOWN_MS) {
                throw new IllegalStateException(
                        "向量不可用（失败冷却中，剩余 " + (FAILURE_COOLDOWN_MS - elapsed) + " ms）");
            }
        }

        if (!computeLock.tryLock()) {
            throw new IllegalStateException("向量正在计算中，本次请求直接降级");
        }

        List<float[]> computed = null;
        try {
            if (vectorsReady) {
                return library;
            }
            if (vectorMapper.ready(library.version(), embeddingBackend.name(), cardIds)) {
                vectorsReady = true;
                lastFailureAt = 0;
                log.info("向量已就绪（存储命中）: {} 张卡, {}", cardIds.size(), vectorMapper.kind());
                return library;
            }
            computed = computeVectors();
            this.vectors = computed;
            this.vectorsReady = true;
            this.lastFailureAt = 0;
        } catch (RuntimeException e) {
            this.lastFailureAt = System.currentTimeMillis();
            log.warn("向量计算失败，{} ms 内不再重试: {}", FAILURE_COOLDOWN_MS, e.getMessage());
            throw e;
        } finally {
            computeLock.unlock();
        }

        // 持久化放在锁外：它失败不影响已就绪的内存向量，也不该让并发请求多等一次写库。
        if (computed != null) {
            persist(computed);
        }
        return library;
    }

    /** 纯计算，不碰任何锁之外的状态，耗时全部在这里（网络调用）。 */
    private List<float[]> computeVectors() {
        List<String> texts = library.cards().stream()
                .map(card -> card.recallText(stripNegativeClause))
                .toList();
        List<float[]> computed = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            computed.addAll(embeddingBackend.model().embed(texts.subList(start, end)));
        }
        if (computed.size() != texts.size()) {
            throw new IllegalStateException("向量数量与卡片数量不一致: " + computed.size() + " vs " + texts.size());
        }
        int dimension = -1;
        for (float[] vector : computed) {
            if (vector == null || vector.length == 0) {
                throw new IllegalStateException("存在空向量");
            }
            if (dimension < 0) {
                dimension = vector.length;
            } else if (dimension != vector.length) {
                throw new IllegalStateException("向量维度不一致: " + dimension + " vs " + vector.length);
            }
        }
        return List.copyOf(computed);
    }

    /** 持久化是尽力而为：失败只影响「重启后是否重算」，不影响当前可用性。 */
    private void persist(List<float[]> computed) {
        try {
            boolean ok = vectorMapper.save(library.version(), embeddingBackend.name(), cardIds, computed);
            if (ok) {
                log.info("向量已持久化: {} 张卡, {} 维, 模型 {}",
                        computed.size(), computed.get(0).length, embeddingBackend.name());
            } else {
                log.warn("向量已就绪（内存），但持久化失败，重启后将重算");
            }
        } catch (RuntimeException e) {
            log.warn("向量持久化异常（内存仍可用，重启后将重算）: {}", e.getMessage());
        }
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
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
