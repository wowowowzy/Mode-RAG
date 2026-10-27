package com.challenger.moderoute.card;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CardLibraryLoadTest {

    @Test
    void 真实卡片库必须满足全部字段约束() {
        List<Card> cards = CardLoader.load(Path.of("data/cards"));

        assertThat(cards).hasSize(48);
        Map<CardDomain, Long> byDomain = cards.stream()
                .collect(Collectors.groupingBy(Card::domain, Collectors.counting()));
        assertThat(byDomain).containsEntry(CardDomain.PLANNING, 16L)
                .containsEntry(CardDomain.DEBATE, 16L)
                .containsEntry(CardDomain.GENERAL, 16L);

        assertThat(cards.stream().map(Card::id).distinct().count()).isEqualTo(48);
        for (Card card : cards) {
            assertThat(card.applicableWhen().codePointCount(0, card.applicableWhen().length()))
                    .as(card.id() + " 的 applicableWhen")
                    .isLessThanOrEqualTo(80);
            assertThat(card.applicableWhen()).as(card.id()).contains("不适用于");
            assertThat(card.steps()).as(card.id()).hasSizeBetween(4, 6);
            assertThat(card.failureModes()).as(card.id()).hasSizeBetween(2, 4);
            assertThat(card.exitConditions()).as(card.id()).hasSizeBetween(1, 3);
            assertThat(card.id()).startsWith(card.domain().key() + "-");
        }
    }

    @Test
    void 库版本为十二位十六进制() {
        String version = LibraryVersion.compute(Path.of("data/cards"));
        assertThat(version).matches("[0-9a-f]{12}");
    }

    @Test
    void 召回文本剔除不适用于从句() {
        Card card = new Card("planning-x", CardDomain.PLANNING, "示例", "排产任务、容量分配；不适用于纯事实查证。",
                List.of("1", "2", "3", "4"), List.of("a", "b"), List.of("c"), "d");
        assertThat(card.recallText(true)).isEqualTo("排产任务、容量分配");
        assertThat(card.recallText(false)).contains("不适用于");
    }
}
