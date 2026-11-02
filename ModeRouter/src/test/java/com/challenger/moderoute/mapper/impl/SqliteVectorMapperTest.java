package com.challenger.moderoute.mapper.impl;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.ScoredCard;
import com.challenger.moderoute.mapper.impl.SqliteVectorMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteVectorMapperTest {

    private static final Path EXTENSION = Path.of("lib/native/vec0.dll");

    @TempDir
    Path temp;

    private SqliteVectorMapper store() {
        Assumptions.assumeTrue(Files.isRegularFile(EXTENSION), "缺少 sqlite-vec 扩展，跳过");
        return new SqliteVectorMapper(temp.resolve("vectors.db"), EXTENSION);
    }

    private static List<String> ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> String.format("card-%02d", i)).toList();
    }

    private static List<float[]> vectors(int count, int dimension) {
        return IntStream.range(0, count)
                .mapToObj(i -> {
                    float[] vector = new float[dimension];
                    vector[i % dimension] = 1f;
                    vector[(i + 1) % dimension] = 0.5f;
                    return vector;
                })
                .toList();
    }

    @Test
    void 存取往返且维度与卡片集合匹配才就绪() {
        try (SqliteVectorMapper store = store()) {
            List<String> ids = ids(5);
            store.save("v1", "model-a", ids, vectors(5, 8));

            assertThat(store.ready("v1", "model-a", ids)).isTrue();
            assertThat(store.ready("v2", "model-a", ids)).isFalse();
            assertThat(store.ready("v1", "model-b", ids)).isFalse();
            assertThat(store.ready("v1", "model-a", ids.subList(0, 4))).isFalse();
        }
    }

    @Test
    void topK按余弦相似度降序且可复现() {
        try (SqliteVectorMapper store = store()) {
            List<String> ids = ids(4);
            float[] a = {1f, 0f, 0f, 0f};
            float[] b = {0.9f, 0.1f, 0f, 0f};
            float[] c = {0f, 1f, 0f, 0f};
            float[] d = {0f, 0f, 1f, 0f};
            store.save("v1", "model-a", ids, List.of(a, b, c, d));

            float[] query = {1f, 0f, 0f, 0f};
            List<ScoredCard> first = store.topK("v1", "model-a", query, 3);
            List<ScoredCard> second = store.topK("v1", "model-a", query, 3);

            assertThat(first).hasSize(3);
            assertThat(first.get(0).cardId()).isEqualTo("card-00");
            assertThat(first.get(1).cardId()).isEqualTo("card-01");
            assertThat(first.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(first.get(1).score()).isCloseTo(0.993884, org.assertj.core.data.Offset.offset(1e-5));
            assertThat(second).isEqualTo(first);
        }
    }

    @Test
    void 维度变化视为未就绪而不是将就() {
        try (SqliteVectorMapper store = store()) {
            List<String> ids = ids(3);
            store.save("v1", "model-a", ids, vectors(3, 8));
            store.save("v1", "model-a", ids, vectors(3, 4));

            assertThat(store.ready("v1", "model-a", ids)).isTrue();
            assertThatThrownBy(() -> store.topK("v1", "model-a", new float[8], 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("维度");
        }
    }

    @Test
    void 重写时清掉旧版本与旧卡片() {
        try (SqliteVectorMapper store = store()) {
            List<String> oldIds = ids(4);
            store.save("v1", "model-a", oldIds, vectors(4, 8));

            List<String> newIds = ids(3).stream().map(id -> id + "-new").toList();
            store.save("v2", "model-a", newIds, vectors(3, 8));

            assertThat(store.ready("v2", "model-a", newIds)).isTrue();
            assertThat(store.ready("v1", "model-a", oldIds)).isFalse();
            assertThat(store.topK("v2", "model-a", new float[8], 10)).hasSize(3);
        }
    }

    @Test
    void 删除数据库文件后回到未就绪() {
        Path db = temp.resolve("vectors.db");
        try (SqliteVectorMapper store = new SqliteVectorMapper(db, EXTENSION)) {
            List<String> ids = ids(3);
            store.save("v1", "model-a", ids, vectors(3, 8));
            assertThat(store.ready("v1", "model-a", ids)).isTrue();
        }
        try {
            Files.deleteIfExists(db);
            Files.deleteIfExists(Path.of(db + "-wal"));
            Files.deleteIfExists(Path.of(db + "-shm"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try (SqliteVectorMapper reopened = new SqliteVectorMapper(db, EXTENSION)) {
            assertThat(reopened.ready("v1", "model-a", ids(3))).isFalse();
        }
    }

    @Test
    void 扩展缺失时明确报错而不是静默降级() {
        SqliteVectorMapper broken = new SqliteVectorMapper(temp.resolve("x.db"), temp.resolve("missing.dll"));
        assertThatThrownBy(broken::probe)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 sqlite-vec 扩展");
    }
}
