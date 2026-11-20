package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.CardRelations;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.GateDecision;
import com.challenger.moderoute.entity.OptionSet;
import com.challenger.moderoute.entity.ScoredCard;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.challenger.moderoute.entity.enums.GateAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P2：候选收缩、规则否决、组合渲染顺序与预算。 */
class P2RelationGraphTest {

    private static Card card(String id, CardDomain domain, String family) {
        return new Card(id, domain, id, "适用条件；不适用于无关任务。",
                List.of("一", "二", "三", "四"), List.of("甲", "乙"), List.of("丙"),
                "输出 JSON", family, CardRelations.EMPTY);
    }

    private static CardLibrary library() {
        return CardLibrary.of("v1", List.of(
                card("planning-window-first", CardDomain.PLANNING, "window"),
                card("planning-capacity-first", CardDomain.PLANNING, "capacity"),
                card("planning-mutex-serialize", CardDomain.PLANNING, "mutex"),
                card("planning-precedence-topo", CardDomain.PLANNING, "precedence"),
                card("general-critical-path", CardDomain.GENERAL, "general"),
                card("general-bounds-first", CardDomain.GENERAL, "general")));
    }

    private static List<ScoredCard> candidates() {
        return List.of(
                new ScoredCard("planning-window-first", 0.9),
                new ScoredCard("planning-capacity-first", 0.8),
                new ScoredCard("planning-mutex-serialize", 0.7),
                new ScoredCard("general-critical-path", 0.6),
                new ScoredCard("planning-precedence-topo", 0.5),
                new ScoredCard("general-bounds-first", 0.4));
    }

    // ---------------------------------------------------------- 候选收缩

    @Test
    void 命中单族时收缩到族内卡加通用卡并补足三张() {
        List<ScoredCard> narrowed = new CandidateNarrower()
                .narrow("订单带最晚完成日和时间窗约束，需要排产", candidates(), library());

        assertThat(narrowed).hasSizeGreaterThanOrEqualTo(3);
        assertThat(narrowed).extracting(ScoredCard::cardId).contains("planning-window-first", "general-critical-path");
        assertThat(narrowed).extracting(ScoredCard::cardId).doesNotContain("planning-mutex-serialize");
    }

    @Test
    void 未命中任何族时原样返回不收缩() {
        List<ScoredCard> original = candidates();
        List<ScoredCard> narrowed = new CandidateNarrower().narrow("今天天气如何", original, library());
        assertThat(narrowed).containsExactlyElementsOf(original);
    }

    @Test
    void 多族命中时并集不超过六张() {
        List<ScoredCard> narrowed = new CandidateNarrower()
                .narrow("任务有互斥约束、先后依赖，还有最晚完成日", candidates(), library());
        assertThat(narrowed).hasSizeLessThanOrEqualTo(6);
    }

    // ---------------------------------------------------------- 规则否决

    @Test
    void 任务无互斥信号时否决mutex卡并改取次高() {
        CardLibrary library = library();
        OptionSet options = OptionSet.ofCardIds(List.of("planning-mutex-serialize", "planning-capacity-first"));
        double[] probs = {0.6, 0.3, 0.1};
        Decision decision = new Decision("test", options, probs, probs, 1.0, null);
        GateDecision gated = new GateDecision(GateAction.INJECT, 'A', "planning-mutex-serialize", 0.6);

        GateDecision result = new RuleVeto().apply("为产线分配订单工时", decision, library, gated);

        assertThat(result.action()).isEqualTo(GateAction.INJECT);
        assertThat(result.cardId()).isEqualTo("planning-capacity-first");
        // OptionSet 按 cardId 字典序排：capacity 在 index 0（概率 0.6），mutex 在 index 1（0.3）
        assertThat(result.confidence()).isEqualTo(0.6);
    }

    @Test
    void 任务含互斥信号时不否决() {
        CardLibrary library = library();
        OptionSet options = OptionSet.ofCardIds(List.of("planning-mutex-serialize", "planning-capacity-first"));
        double[] probs = {0.6, 0.3, 0.1};
        Decision decision = new Decision("test", options, probs, probs, 1.0, null);
        GateDecision gated = new GateDecision(GateAction.INJECT, 'A', "planning-mutex-serialize", 0.6);

        GateDecision result = new RuleVeto().apply("两台机器不能同日使用，存在互斥约束", decision, library, gated);
        assertThat(result.cardId()).isEqualTo("planning-mutex-serialize");
    }

    @Test
    void 全部候选被否决时返回FALLBACK() {
        CardLibrary lib = CardLibrary.of("v1", List.of(
                card("planning-mutex-serialize", CardDomain.PLANNING, "mutex"),
                card("planning-mutex-split", CardDomain.PLANNING, "mutex")));
        OptionSet options = OptionSet.ofCardIds(List.of("planning-mutex-serialize", "planning-mutex-split"));
        double[] probs = {0.6, 0.3, 0.1};
        Decision decision = new Decision("test", options, probs, probs, 1.0, null);
        GateDecision gated = new GateDecision(GateAction.INJECT, 'A', "planning-mutex-serialize", 0.6);

        GateDecision result = new RuleVeto().apply("普通排产任务", decision, lib, gated);
        assertThat(result.action()).isEqualTo(GateAction.FALLBACK);
        assertThat(result.cardId()).isNull();
    }

    // ---------------------------------------------------------- 组合渲染

    @Test
    void planning渲染追加约束自检_节点数包含关系边() {
        Card card = new Card("planning-capacity-first", CardDomain.PLANNING, "容量先行",
                "适用；不适用。", List.of("一", "二", "三", "四"), List.of("甲", "乙"), List.of("丙"),
                "输出 JSON", "capacity",
                new CardRelations(null, List.of("planning-balance-load"), List.of(), List.of(), List.of()));

        CardComposer.Composed composed = new CardComposer().compose(card, "BASE");

        assertThat(composed.prompt()).startsWith("BASE");
        assertThat(composed.prompt()).contains("约束自检");
        assertThat(composed.composedCards()).containsExactly("planning-capacity-first");
        assertThat(composed.graphNodes()).isEqualTo(2); // 自身 + 1 条边
    }

    @Test
    void debate渲染追加先复述最强论点() {
        Card card = card("debate-criteria-first", CardDomain.DEBATE, "criteria");
        CardComposer.Composed composed = new CardComposer().compose(card, "BASE");
        assertThat(composed.prompt()).contains("先复述对方最强论点");
    }

    @Test
    void 超出token预算时放弃后置块() {
        String huge = "X".repeat(4000);
        Card card = card("planning-window-first", CardDomain.PLANNING, "window");
        CardComposer.Composed composed = new CardComposer().compose(card, huge);
        assertThat(composed.prompt()).isEqualTo(huge);
    }
}
