package com.challenger.moderoute.mapper.impl;

import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteCardMapperTest {

    @TempDir
    Path temp;

    private SqliteCardMapper mapper() {
        ModeRouteProperties properties = new ModeRouteProperties();
        properties.getRecall().setSqlitePath(temp.resolve("cards.db").toString());
        return new SqliteCardMapper(properties, new ObjectMapper());
    }

    private static Card card(String id, String name) {
        return new Card(id, CardDomain.DEBATE, name, "立场对比任务；不适用于单方观点。",
                List.of("一", "二", "三", "四"), List.of("甲", "乙"), List.of("丙"), "输出 debate_v1");
    }

    @Test
    void 空库时没有版本() {
        try (SqliteCardMapper mapper = mapper()) {
            assertThat(mapper.loadedVersion()).isEmpty();
            assertThat(mapper.loadAll()).isEmpty();
        }
    }

    @Test
    void 整批替换后可原样读回且顺序稳定() {
        try (SqliteCardMapper mapper = mapper()) {
            List<Card> cards = List.of(card("debate-a", "甲卡"), card("debate-b", "乙卡"));
            mapper.replaceAll("v1", cards);

            assertThat(mapper.loadedVersion()).contains("v1");
            assertThat(mapper.loadAll()).isEqualTo(cards);
        }
    }

    @Test
    void 重新导入会清掉旧版本卡片() {
        try (SqliteCardMapper mapper = mapper()) {
            mapper.replaceAll("v1", List.of(card("debate-a", "甲卡"), card("debate-b", "乙卡")));
            mapper.replaceAll("v2", List.of(card("debate-c", "丙卡")));

            assertThat(mapper.loadedVersion()).contains("v2");
            assertThat(mapper.loadAll()).extracting(Card::id).containsExactly("debate-c");
        }
    }

    @Test
    void 数组字段按JSON往返不丢内容() {
        try (SqliteCardMapper mapper = mapper()) {
            Card card = new Card("planning-x", CardDomain.PLANNING, "测试",
                    "排产；不适用于查证。",
                    List.of("步骤一", "步骤二", "步骤三", "步骤四"),
                    List.of("失败一", "失败二"),
                    List.of("退出"),
                    "输出 assignments");
            mapper.replaceAll("v1", List.of(card));

            Card loaded = mapper.loadAll().get(0);
            assertThat(loaded.steps()).containsExactly("步骤一", "步骤二", "步骤三", "步骤四");
            assertThat(loaded.failureModes()).containsExactly("失败一", "失败二");
            assertThat(loaded.exitConditions()).containsExactly("退出");
            assertThat(loaded.domain()).isEqualTo(CardDomain.PLANNING);
        }
    }
}
