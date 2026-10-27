package com.challenger.moderoute.decide;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptionSetTest {

    @Test
    void 选项按卡片字典序映射且None占最后一位() {
        OptionSet options = OptionSet.ofCardIds(List.of("planning-z", "planning-a", "debate-m"));
        assertThat(options.options()).extracting(OptionSet.Option::letter)
                .containsExactly('A', 'B', 'C', 'D');
        assertThat(options.options()).extracting(OptionSet.Option::cardId)
                .containsExactly("debate-m", "planning-a", "planning-z", null);
        assertThat(options.noneLetter()).isEqualTo('D');
        assertThat(options.isNone('D')).isTrue();
        assertThat(options.cardIdOf('A')).contains("debate-m");
    }

    @Test
    void 最多二十五张卡加None() {
        List<String> ids = java.util.stream.IntStream.range(0, 26)
                .mapToObj(i -> String.format("x-%02d", i))
                .toList();

        OptionSet options = OptionSet.ofCardIds(ids.subList(0, 25));
        assertThat(options.size()).isEqualTo(26);
        assertThat(options.noneLetter()).isEqualTo('Z');

        assertThatThrownBy(() -> OptionSet.ofCardIds(ids)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 空候选被拒绝() {
        assertThatThrownBy(() -> OptionSet.ofCardIds(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
