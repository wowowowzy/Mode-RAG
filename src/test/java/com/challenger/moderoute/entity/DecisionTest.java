package com.challenger.moderoute.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTest {

    private static Decision decision(double... probabilities) {
        OptionSet options = OptionSet.ofCardIds(List.of("planning-a", "planning-b"));
        return new Decision("test", options, probabilities, probabilities, 1.0, null);
    }

    @Test
    void 平票时None胜出() {
        Decision decision = decision(0.5, 0.5, 0.5);
        assertThat(decision.argmaxLetter()).isEqualTo(decision.options().noneLetter());
    }

    @Test
    void 卡片严格更大时卡片胜出() {
        Decision decision = decision(0.6, 0.4, 0.2);
        assertThat(decision.argmaxLetter()).isEqualTo('A');
    }

    @Test
    void 仅卡片之间平票时不选None() {
        // A=B=0.4，None=0.2：None 不参与这组平票，仍然选字典序靠前的 A
        Decision decision = decision(0.4, 0.4, 0.2);
        assertThat(decision.argmaxLetter()).isEqualTo('A');
    }

    @Test
    void 容差内的差异视为平票() {
        Decision decision = decision(0.5, 0.4999999999, 0.5);
        assertThat(decision.argmaxLetter()).isEqualTo(decision.options().noneLetter());
    }

    @Test
    void 单卡片候选时None仍占末位() {
        OptionSet options = OptionSet.ofCardIds(List.of("planning-only"));
        assertThat(options.size()).isEqualTo(2);
        assertThat(options.noneLetter()).isEqualTo('B');
    }
}
