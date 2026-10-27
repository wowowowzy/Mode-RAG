package com.challenger.moderoute.recall;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储层检索耗时对比：SQLite（sqlite-vec 距离函数）vs 单文件 JSON（进程内余弦）。
 * 只作为量级参考与回归哨兵，断言放了很宽的上限，避免 CI 抖动。
 */
class VectorStoreLatencyTest {

    private static final Path EXTENSION = Path.of("lib/native/vec0.dll");

    @TempDir
    Path temp;

    @Test
    void sqlite与json检索都在毫秒以内() {
        Assumptions.assumeTrue(Files.isRegularFile(EXTENSION), "缺少 sqlite-vec 扩展，跳过");

        int cards = 48;
        int dimension = 1024;
        Random random = new Random(7);
        List<String> ids = IntStream.range(0, cards).mapToObj(i -> String.format("card-%02d", i)).toList();
        List<float[]> vectors = IntStream.range(0, cards)
                .mapToObj(i -> {
                    float[] vector = new float[dimension];
                    for (int j = 0; j < dimension; j++) {
                        vector[j] = (float) random.nextGaussian();
                    }
                    return vector;
                })
                .toList();
        float[] query = new float[dimension];
        for (int j = 0; j < dimension; j++) {
            query[j] = (float) random.nextGaussian();
        }

        try (SqliteVectorStore sqlite = new SqliteVectorStore(temp.resolve("bench.db"), EXTENSION)) {
            sqlite.save("v1", "bench", ids, vectors);
            JsonVectorStore json = new JsonVectorStore(new VectorCache(
                    properties(temp.resolve("json")), new com.fasterxml.jackson.databind.ObjectMapper()));
            json.save("v1", "bench", ids, vectors);

            double sqliteMicros = measure(() -> sqlite.topK("v1", "bench", query, 8));
            double jsonMicros = measure(() -> json.topK("v1", "bench", query, 8));

            System.out.printf("store benchmark: sqlite=%.0f us, json=%.0f us%n", sqliteMicros, jsonMicros);
            assertThat(sqliteMicros).isLessThan(50_000);
            assertThat(jsonMicros).isLessThan(50_000);
        }
    }

    private static double measure(Runnable action) {
        for (int i = 0; i < 50; i++) {
            action.run();
        }
        int rounds = 300;
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            action.run();
        }
        return (System.nanoTime() - start) / 1000.0 / rounds;
    }

    private static com.challenger.moderoute.config.ModeRouteProperties properties(Path cacheDir) {
        var properties = new com.challenger.moderoute.config.ModeRouteProperties();
        properties.getRecall().setCacheDir(cacheDir.toString());
        return properties;
    }
}
