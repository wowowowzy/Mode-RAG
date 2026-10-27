package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.util.TokenEstimator;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.challenger.moderoute.service.impl.PlaceboRenderer;
import com.challenger.moderoute.service.impl.SkeletonRenderer;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.enums.CardDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendererTest {

    private final SkeletonRenderer renderer = new SkeletonRenderer();
    private final PlaceboRenderer placeboRenderer = new PlaceboRenderer();

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
        String prompt = renderer.render(card(), 2000);
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
    void 预算不足时先裁输出格式示例再裁失败模式() {
        int full = TokenEstimator.estimate(renderer.render(card(), 2000));
        assertThat(full).isGreaterThan(50);

        String trimmed = renderer.render(card(), 110);
        assertThat(TokenEstimator.estimate(trimmed)).isLessThanOrEqualTo(110);
        assertThat(trimmed).doesNotContain("常见失败模式");
        assertThat(trimmed).contains("停止条件");
    }

    @Test
    void 渲染是确定性的() {
        assertThat(renderer.render(card(), 400)).isEqualTo(renderer.render(card(), 400));
    }

    @Test
    void placebo等长且内容通用() {
        String base = renderer.render(card(), 400);
        String placebo = placeboRenderer.render(CardDomain.PLANNING, base.length());

        assertThat(placebo.length()).isBetween(base.length() - 40, base.length() + 40);
        assertThat(placebo).doesNotContain("统计可用工时").contains("通用分步推进");
        assertThat(placebo).isEqualTo(placeboRenderer.render(CardDomain.PLANNING, base.length()));
    }

    @Test
    void token估算与截断() {
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("中文四字")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("abcdefgh")).isEqualTo(2);

        String longText = "第一行内容\n第二行内容\n第三行内容\n第四行内容";
        String cut = TokenEstimator.truncateToTokens(longText, 8);
        assertThat(TokenEstimator.estimate(cut)).isLessThanOrEqualTo(8);
        assertThat(cut).doesNotContain("第四行内容");
    }
}
