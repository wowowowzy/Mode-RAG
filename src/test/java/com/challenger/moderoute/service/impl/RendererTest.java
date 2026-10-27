package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.util.TokenEstimator;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.enums.CardDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendererTest {

    private final SkeletonRenderer renderer = new SkeletonRenderer();

    private Card card() {
        return new Card("planning-demo", CardDomain.PLANNING, "演示骨架",
                "容量受限的排产任务；不适用于纯事实查证。",
                List.of("统计可用工时", "按工时降序安置", "扣减余量", "复核超载"),
                List.of("只看总量", "忽略单日上限"),
                List.of("放不下时报告缺口"),
                "输出 assignments 与 verification");
    }

    @Test
    void 完整渲染包含全部段落与输出格式() {
        String prompt = renderer.render(card());
        assertThat(prompt).contains("【推理骨架：演示骨架】")
                .contains("适用条件：")
                .contains("1. 统计可用工时")
                .contains("常见失败模式（逐条自查）：")
                .contains("停止条件（满足即停止并说明，不要硬给结论）：")
                .contains("输出格式：")
                .contains("plan_v1")
                .contains("assignments");
    }

    @Test
    void 不做长度裁剪() {
        String prompt = renderer.render(card());
        int tokens = TokenEstimator.estimate(prompt);
        assertThat(tokens).isGreaterThan(50);
        assertThat(prompt).contains("常见失败模式").contains("停止条件").contains("plan_v1");
    }

    @Test
    void 渲染是确定性的() {
        assertThat(renderer.render(card())).isEqualTo(renderer.render(card()));
    }

    @Test
    void token估算() {
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("中文四字")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("abcdefgh")).isEqualTo(2);
    }
}
