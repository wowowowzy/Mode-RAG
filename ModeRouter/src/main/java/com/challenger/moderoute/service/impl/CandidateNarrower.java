package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.ScoredCard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P2 候选收缩：用轻量关键词把任务归到一个/多个约束族，只让 JEV 在族内卡 + 少量通用卡中选择。
 *
 * 保守原则：
 * - 未命中任何族 → 原样返回 P1 候选，不收缩；
 * - 命中多族 → 并集，上限 6 张；
 * - 命中 1 族 → 该族卡 + 1 张通用卡；
 * - 候选数不足 3 → 从高分候选中补足；None 由 OptionSet 永远保留。
 */
@Component
public class CandidateNarrower {

    private static final int MAX_NARROWED = 6;
    private static final int MIN_NARROWED = 3;

    private static final String PLANNING_GENERAL_CARD = "general-critical-path";
    private static final String DEBATE_GENERAL_CARD = "general-first-principles";

    private static final Map<String, List<String>> PLANNING_SIGNALS = Map.ofEntries(
            Map.entry("window", List.of("最晚完成日", "最晚完成时间", "时间窗", "截止", "窗口", "区间", "deadline")),
            Map.entry("mutex", List.of("互斥", "不能同", "冲突", "同一资源", "同一天", "争用")),
            Map.entry("precedence", List.of("先后", "依赖", "前置", "紧前", "紧后", "顺序")),
            Map.entry("capacity", List.of("工时上限", "产能", "容量", "每天最多", "单日", "每日可用工时", "负荷")),
            Map.entry("ratio", List.of("比例", "份额", "占比", "均衡", "公平")),
            Map.entry("makespan", List.of("总工期", "最短工期", "尽快完成")),
            Map.entry("repair", List.of("修复", "调整", "重排", "局部修改", "变更")),
            Map.entry("feasibility", List.of("可行性", "下界", "能否完成")));

    private static final Map<String, List<String>> DEBATE_SIGNALS = Map.ofEntries(
            Map.entry("fact-value", List.of("事实", "数据", "价值观", "道德", "立场")),
            Map.entry("premise", List.of("前提", "假设", "分歧来源")),
            Map.entry("argument", List.of("论点", "论据", "推理链", "谬误")),
            Map.entry("evidence", List.of("证据", "来源", "可信度", "证明", "证伪")),
            Map.entry("adversarial", List.of("双方", "对方", "反驳", "steelman", "红队", "最强论点")),
            Map.entry("criteria", List.of("标准", "准则", "权衡", "取舍", "决策")));

    /** 输入候选已按分数降序；输出保持该顺序，保证确定性。 */
    public List<ScoredCard> narrow(String task, List<ScoredCard> candidates, CardLibrary library) {
        if (task == null || candidates == null || candidates.size() <= MIN_NARROWED) {
            return candidates;
        }

        boolean debate = isDebate(candidates, library);
        Map<String, List<String>> signals = debate ? DEBATE_SIGNALS : PLANNING_SIGNALS;
        String generalCard = debate ? DEBATE_GENERAL_CARD : PLANNING_GENERAL_CARD;

        Set<String> matchedFamilies = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : signals.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (task.contains(keyword)) {
                    matchedFamilies.add(entry.getKey());
                    break;
                }
            }
        }
        if (matchedFamilies.isEmpty()) {
            return candidates; // 未命中任何族：保守，不收缩
        }

        Set<String> allowedIds = new HashSet<>();
        for (Card card : library.cards()) {
            if (matchedFamilies.contains(card.family()) || card.id().equals(generalCard)) {
                allowedIds.add(card.id());
            }
        }

        List<ScoredCard> narrowed = new ArrayList<>();
        for (ScoredCard candidate : candidates) {
            if (allowedIds.contains(candidate.cardId())) {
                narrowed.add(candidate);
                if (narrowed.size() >= MAX_NARROWED) {
                    break;
                }
            }
        }
        if (narrowed.size() < MIN_NARROWED) {
            for (ScoredCard candidate : candidates) {
                if (narrowed.size() >= MIN_NARROWED) {
                    break;
                }
                if (!allowedIds.contains(candidate.cardId())) {
                    narrowed.add(candidate);
                }
            }
        }
        return narrowed.size() >= MIN_NARROWED ? List.copyOf(narrowed) : candidates;
    }

    private static boolean isDebate(List<ScoredCard> candidates, CardLibrary library) {
        for (ScoredCard candidate : candidates) {
            Card card = library.byId().get(candidate.cardId());
            if (card != null) {
                return card.domain().key().equals("debate");
            }
        }
        return false;
    }
}
