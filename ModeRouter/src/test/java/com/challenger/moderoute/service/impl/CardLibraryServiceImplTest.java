package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.client.EmbeddingBackend;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.ScoredCard;
import com.challenger.moderoute.mapper.VectorMapper;
import com.challenger.moderoute.mapper.impl.SqliteCardMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 P0-1（并发不排队 / 失败冷却）与 P0-2（持久化失败仍可用）。
 */
class CardLibraryServiceImplTest {

    @TempDir
    Path temp;

    /** 测试里创建的 card mapper 持有 SQLite 连接，必须显式关闭，否则 @TempDir 删不掉。 */
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void closeResources() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 关闭失败不覆盖测试本身的结论
            }
        }
        closeables.clear();
    }

    /** 可控的假模型：能延迟、能失败、能计数。 */
    static final class FakeModel implements EmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        final long delayMs;
        final boolean fail;

        FakeModel(long delayMs, boolean fail) {
            this.delayMs = delayMs;
            this.fail = fail;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fail) {
                throw new IllegalStateException("模拟 embedding 失败");
            }
            List<Embedding> out = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                float[] vector = new float[8];
                vector[0] = 1f;
                vector[1] = (float) (i + 1);
                out.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return call(new EmbeddingRequest(List.of(document.getText()), null))
                    .getResults().get(0).getOutput();
        }

        @Override
        public int dimensions() {
            return 8;
        }
    }

    /** 只关心「有没有存」和「存成功没」。 */
    static final class StubStore implements VectorMapper {
        volatile boolean persistOk = true;
        volatile boolean saved = false;
        volatile int saveCalls = 0;

        @Override
        public String kind() {
            return "stub";
        }

        @Override
        public boolean ready(String libraryVersion, String model, List<String> cardIds) {
            return false;
        }

        @Override
        public List<ScoredCard> topK(String libraryVersion, String model, float[] query, int limit) {
            return List.of();
        }

        @Override
        public boolean save(String libraryVersion, String model, List<String> cardIds, List<float[]> vectors) {
            saved = true;
            saveCalls++;
            return persistOk;
        }
    }

    private CardLibraryServiceImpl service(EmbeddingModel model, VectorMapper store) {
        ModeRouteProperties properties = new ModeRouteProperties();
        properties.setCardsDir("data/cards");
        properties.getRecall().setSqlitePath(temp.resolve("cards.db").toString());
        properties.getRecall().setBatchSize(10);
        SqliteCardMapper cardMapper = new SqliteCardMapper(properties, new ObjectMapper());
        closeables.add(cardMapper);
        return new CardLibraryServiceImpl(properties, new EmbeddingBackend(model, "test-model"), store, cardMapper);
    }

    @Test
    void 并发请求不排队且只计算一次() throws Exception {
        FakeModel model = new FakeModel(500, false);
        CardLibraryServiceImpl service = service(model, new StubStore());

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.ensureVectors();
                    return "OK";
                } catch (RuntimeException e) {
                    return "FAIL";
                }
            }));
        }

        long begin = System.currentTimeMillis();
        start.countDown();
        int ok = 0;
        int fail = 0;
        for (Future<String> future : futures) {
            if ("OK".equals(future.get(10, TimeUnit.SECONDS))) {
                ok++;
            } else {
                fail++;
            }
        }
        long elapsed = System.currentTimeMillis() - begin;
        pool.shutdownNow();

        assertThat(ok).isEqualTo(1);
        assertThat(fail).isEqualTo(threads - 1);
        // 48 张卡 / batch 10 = 5 批，每批 500ms，单次计算约 2500ms。
        // 若发生排队（10 个请求各算一遍）会是 25000ms 量级，所以 4000ms 足以区分。
        assertThat(elapsed).isLessThan(4_000L);
        assertThat(model.calls.get()).isEqualTo(5);
    }

    @Test
    void 失败后冷却期内不再调用模型() {
        FakeModel model = new FakeModel(0, true);
        CardLibraryServiceImpl service = service(model, new StubStore());

        assertThatThrownBy(service::ensureVectors)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟 embedding 失败");
        int afterFirst = model.calls.get();
        assertThat(afterFirst).isEqualTo(1);

        assertThatThrownBy(service::ensureVectors)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冷却");

        assertThat(model.calls.get()).isEqualTo(afterFirst);
        assertThat(service.vectorsReady()).isFalse();
    }

    @Test
    void 持久化失败时内存向量仍可检索() {
        FakeModel model = new FakeModel(0, false);
        StubStore store = new StubStore();
        store.persistOk = false;
        CardLibraryServiceImpl service = service(model, store);

        service.ensureVectors();

        assertThat(store.saved).isTrue();
        assertThat(store.saveCalls).isEqualTo(1);
        assertThat(service.vectorsReady()).isTrue();
        assertThat(service.memoryVectors()).isPresent();
        assertThat(service.memoryVectors().get()).hasSize(48);

        float[] query = new float[]{1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        Optional<List<ScoredCard>> top = service.topKInMemory(query, 5);
        assertThat(top).isPresent();
        assertThat(top.get()).hasSize(5);
        assertThat(top.get()).isSortedAccordingTo((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            return byScore != 0 ? byScore : a.cardId().compareTo(b.cardId());
        });
    }

    @Test
    void 未就绪时内存检索返回空而不是抛异常() {
        CardLibraryServiceImpl service = service(new FakeModel(0, false), new StubStore());
        assertThat(service.topKInMemory(new float[]{1f, 0f}, 3)).isEmpty();
        assertThat(service.memoryVectors()).isEmpty();
    }
}
