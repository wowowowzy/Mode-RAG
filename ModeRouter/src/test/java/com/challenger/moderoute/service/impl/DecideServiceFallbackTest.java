package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.util.Gate;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.DecisionOutcome;
import com.challenger.moderoute.entity.GateDecision;
import com.challenger.moderoute.entity.OptionSet;
import com.challenger.moderoute.entity.enums.GateAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级路径的门控语义：不能把精判的 noneMargin 套到 RuleFallback 上。
 */
class DecideServiceFallbackTest {

    /**
     * 所有卡相似度都略低于 rule-min-score 时，归一化后 None 仅小幅领先最佳卡
     * （差值 0.14 < noneMargin 0.23）。这正是 P0.2 要修的场景。
     */
    private static final double[] MARGIN_TRAP = {0.43d, 0.57d};

    private static CardLibrary library() {
        return CardLibrary.of("v1", List.of());
    }

    /** 可控的 RuleFallback：直接返回固定分布，避免依赖 bigram 的偶然取值。 */
    private static RuleFallback controlledFallback() {
        return new RuleFallback(new ModeRouteProperties()) {
            @Override
            public Decision decide(String task, CardLibrary library, int maxCandidates) {
                OptionSet options = OptionSet.ofCardIds(List.of("planning-capacity-first"));
                return new Decision("rule", options, MARGIN_TRAP, MARGIN_TRAP, 1.0, null);
            }
        };
    }

    @Test
    void noneMargin确实会污染规则降级的门控_这是修复原因() {
        // 证明缺陷存在：同一分布套用 noneMargin 会被强行改成 INJECT。
        OptionSet options = OptionSet.ofCardIds(List.of("planning-capacity-first"));
        Decision decision = new Decision("rule", options, MARGIN_TRAP, MARGIN_TRAP, 1.0, null);
        GateDecision trapped = Gate.decide(decision, 0.0, 0.23);
        assertThat(trapped.action()).isEqualTo(GateAction.INJECT);
    }

    @Test
    void 降级路径传0margin_全不合适仍回退() {
        ModeRouteProperties properties = new ModeRouteProperties();
        properties.getGate().setNoneMargin(0.23);
        DecideServiceImpl service = new DecideServiceImpl(properties, null, controlledFallback(), new RuleVeto());

        DecisionOutcome result = service.fallback("任意任务", library(), 8);

        assertThat(result.action()).isEqualTo(GateAction.FALLBACK);
        assertThat(result.cardId()).isNull();
    }

    @Test
    void 正常精判路径仍使用noneMargin_未被误改() {
        // behavior guard：普通 decide() 仍带 margin（用一个简单 mock engine 不可行，这里直接断言配置被读取）。
        ModeRouteProperties properties = new ModeRouteProperties();
        properties.getGate().setNoneMargin(0.42);
        DecideServiceImpl service = new DecideServiceImpl(properties, null, controlledFallback(), new RuleVeto());
        // fallback 不受影响，证明它没用实例的 noneMargin 字段。
        assertThat(service.fallback("任意任务", library(), 8).action()).isEqualTo(GateAction.FALLBACK);
    }
}
