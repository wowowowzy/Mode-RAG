package com.challenger.moderoute.decide;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CalibratorAndGateTest {

    @Test
    void 温度为一不改动分布() {
        double[] probs = {0.6, 0.3, 0.1};
        assertThat(Calibrator.apply(probs, 1.0)).containsExactly(probs);
    }

    @Test
    void 高温让分布更平_低温让分布更尖() {
        double[] probs = {0.6, 0.3, 0.1};
        double[] flat = Calibrator.apply(probs, 3.0);
        double[] sharp = Calibrator.apply(probs, 0.3);

        assertThat(flat[0]).isLessThan(0.6);
        assertThat(sharp[0]).isGreaterThan(0.6);
        assertThat(flat[0] + flat[1] + flat[2]).isCloseTo(1.0, within(1e-9));
        assertThat(sharp[0] + sharp[1] + sharp[2]).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void None胜出时为回退() {
        OptionSet options = OptionSet.ofCardIds(java.util.List.of("a-1", "b-1"));
        Decision decision = new Decision("test", options,
                new double[]{0.2, 0.1, 0.7}, new double[]{0.2, 0.1, 0.7}, 1.0, null);

        GateDecision gate = Gate.decide(decision, 0.0);
        assertThat(gate.action()).isEqualTo(GateAction.FALLBACK);
        assertThat(gate.cardId()).isNull();
        assertThat(gate.confidence()).isEqualTo(0.7);
    }

    @Test
    void 卡片胜出时注入并带置信度() {
        OptionSet options = OptionSet.ofCardIds(java.util.List.of("a-1", "b-1"));
        Decision decision = new Decision("test", options,
                new double[]{0.75, 0.2, 0.05}, new double[]{0.75, 0.2, 0.05}, 1.0, 0.6);

        GateDecision gate = Gate.decide(decision, 0.0);
        assertThat(gate.action()).isEqualTo(GateAction.INJECT);
        assertThat(gate.cardId()).isEqualTo("a-1");
        assertThat(gate.confidence()).isEqualTo(0.75);
    }

    @Test
    void 注入下限只作为可选安全阀() {
        OptionSet options = OptionSet.ofCardIds(java.util.List.of("a-1", "b-1"));
        Decision decision = new Decision("test", options,
                new double[]{0.4, 0.35, 0.25}, new double[]{0.4, 0.35, 0.25}, 1.0, null);

        assertThat(Gate.decide(decision, 0.0).action()).isEqualTo(GateAction.INJECT);
        assertThat(Gate.decide(decision, 0.5).action()).isEqualTo(GateAction.FALLBACK);
    }
}
