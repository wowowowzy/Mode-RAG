package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.util.Gate;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.challenger.moderoute.entity.enums.GateAction;
import com.challenger.moderoute.service.impl.RuleFallback;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleFallbackTest {

    private static Card card(String id, String applicableWhen) {
        return new Card(id, CardDomain.PLANNING, id, applicableWhen,
                List.of("一", "二", "三", "四"), List.of("甲", "乙"), List.of("丙"), "输出 assignments");
    }

    private static CardLibrary library() {
        return CardLibrary.of("v1", List.of(
                card("planning-capacity-first", "多条产线按工时容量分配订单、要求负荷均衡的排产任务；不适用于纯事实查证。"),
                card("planning-window-first", "订单带最晚完成日、需要先保时间窗再压工期的排产任务；不适用于无截止日的分配。"),
                card("debate-steelman-each", "立场分析、方案对比、论证评估；不适用于只有单方观点的讨论。")));
    }

    private static RuleFallback fallback(double minScore) {
        ModeRouteProperties properties = new ModeRouteProperties();
        properties.getDecider().setRuleMinScore(minScore);
        return new RuleFallback(properties);
    }

    @Test
    void 有词面重合时选卡且概率归一() {
        Decision decision = fallback(0.01).decide("产线每日可用工时有限，订单需要按容量排产", library(), 8);

        assertThat(decision.engine()).isEqualTo("rule");
        assertThat(decision.argmaxLetter()).isNotEqualTo(decision.options().noneLetter());
        double sum = 0;
        for (double p : decision.probabilities()) {
            sum += p;
        }
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 词面完全不重合时投向None() {
        Decision decision = fallback(0.9).decide("今天午饭吃什么比较好", library(), 8);
        assertThat(decision.argmaxLetter()).isEqualTo(decision.options().noneLetter());
        assertThat(Gate.decide(decision, 0.0).action()).isEqualTo(GateAction.FALLBACK);
    }

    @Test
    void 相同输入结果完全确定() {
        Decision first = fallback(0.02).decide("订单带最晚完成日，需要排产", library(), 8);
        Decision second = fallback(0.02).decide("订单带最晚完成日，需要排产", library(), 8);
        assertThat(second.options().options()).isEqualTo(first.options().options());
        assertThat(second.probabilities()).containsExactly(first.probabilities());
    }

    @Test
    void 所有卡都低于阈值时回退() {
        // 「今天午饭吃什么」与三张卡没有任何共同 bigram
        Decision decision = fallback(0.04).decide("今天午饭吃什么比较好", library(), 8);
        assertThat(decision.argmaxLetter()).isEqualTo(decision.options().noneLetter());
        assertThat(decision.maxProbability())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(Gate.decide(decision, 0.0).action()).isEqualTo(GateAction.FALLBACK);
    }

    @Test
    void 阈值参与竞争且None概率可解释() {
        Decision decision = fallback(0.04).decide("产线每日可用工时有限，订单需要按容量排产", library(), 8);
        assertThat(decision.argmaxLetter()).isNotEqualTo(decision.options().noneLetter());

        // None 的代表分数是 minScore，所以它的概率不应为 0
        int noneIndex = decision.options().size() - 1;
        assertThat(decision.probabilities()[noneIndex]).isGreaterThan(0d);
        double sum = 0d;
        for (double p : decision.probabilities()) {
            sum += p;
        }
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 阈值非正时构造失败() {
        assertThatThrownBy(() -> fallback(0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rule-min-score");
        assertThatThrownBy(() -> fallback(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
