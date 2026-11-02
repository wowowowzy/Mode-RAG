package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.common.util.TextSimilarity;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.OptionSet;
import com.challenger.moderoute.entity.ScoredCard;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则降级：embedding 或精判不可用时，用字符二元组相似度选卡。
 * 结果标记为 DEGRADED，不伪装成正常召回。
 */
@Service
public class RuleFallback {

    private static final int DEFAULT_CANDIDATES = 8;
    private static final int MAX_CANDIDATES = 25;

    private final double minScore;
    private final boolean stripNegativeClause;

    public RuleFallback(ModeRouteProperties properties) {
        this.minScore = properties.getDecider().getRuleMinScore();
        this.stripNegativeClause = properties.getRecall().isStripNegativeClause();
        if (!(minScore > 0)) {
            throw new IllegalArgumentException(
                    "moderoute.decider.rule-min-score 必须 > 0，否则降级路径永远不会回退（当前 " + minScore + "）");
        }
    }

    public Decision decide(String task, CardLibrary library, int maxCandidates) {
        List<ScoredCard> scored = new ArrayList<>(library.cards().size());
        for (Card card : library.cards()) {
            double score = TextSimilarity.bigramCosine(task, card.recallText(stripNegativeClause));
            scored.add(new ScoredCard(card.id(), score));
        }
        scored.sort(Comparator.comparingDouble(ScoredCard::score).reversed()
                .thenComparing(ScoredCard::cardId));

        int limit = Math.max(1, Math.min(maxCandidates <= 0 ? DEFAULT_CANDIDATES : maxCandidates, MAX_CANDIDATES));
        List<ScoredCard> top = scored.subList(0, Math.min(limit, scored.size()));
        OptionSet options = OptionSet.ofCandidates(top);
        Map<String, Double> byId = new HashMap<>();
        top.forEach(item -> byId.put(item.cardId(), item.score()));

        double[] probabilities = new double[options.size()];
        int noneIndex = options.size() - 1;
        for (int i = 0; i < noneIndex; i++) {
            probabilities[i] = Math.max(0d, byId.getOrDefault(options.options().get(i).cardId(), 0d));
        }
        // None 的代表分数就是阈值本身：低于阈值的卡没有资格胜出，
        // 于是「所有卡都不合适」时 None 自然成为最大项。
        probabilities[noneIndex] = Math.max(0d, minScore);

        double sum = 0d;
        for (double value : probabilities) {
            sum += value;
        }
        if (sum <= 0d) {
            Arrays.fill(probabilities, 0d);
            probabilities[noneIndex] = 1d;
        } else {
            for (int i = 0; i < probabilities.length; i++) {
                probabilities[i] /= sum;
            }
        }
        return new Decision("rule", options, probabilities, probabilities, 1.0, null);
    }
}
