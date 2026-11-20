package com.challenger.moderoute.service.impl;

import com.challenger.moderoute.client.DecisionEngine;
import com.challenger.moderoute.common.util.Calibrator;
import com.challenger.moderoute.common.util.Gate;
import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.DecisionOutcome;
import com.challenger.moderoute.entity.EngineScores;
import com.challenger.moderoute.entity.GateDecision;
import com.challenger.moderoute.entity.OptionSet;
import com.challenger.moderoute.entity.ScoredCard;
import com.challenger.moderoute.service.DecideService;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 精判编排：选项映射 → 引擎打分 → 温度缩放 → 门控。
 * 引擎本身在 client 包，这里不关心它是本地 JEV、远程 JEV 还是 mock。
 */
@Service
public class DecideServiceImpl implements DecideService {

    private final DecisionEngine decisionEngine;
    private final RuleFallback ruleFallback;
    private final RuleVeto ruleVeto;
    private final double temperature;
    private final double injectFloor;
    private final double noneMargin;

    public DecideServiceImpl(ModeRouteProperties properties,
                             DecisionEngine decisionEngine,
                             RuleFallback ruleFallback,
                             RuleVeto ruleVeto) {
        this.decisionEngine = decisionEngine;
        this.ruleFallback = ruleFallback;
        this.ruleVeto = ruleVeto;
        this.temperature = properties.getDecider().getTemperature();
        this.injectFloor = properties.getGate().getInjectFloor();
        this.noneMargin = properties.getGate().getNoneMargin();
    }

    @Override
    public DecisionOutcome decide(String task, List<ScoredCard> candidates, CardLibrary library) {
        OptionSet options = OptionSet.ofCandidates(candidates);
        Map<String, String> descriptions = new HashMap<>();
        for (ScoredCard candidate : candidates) {
            Card card = library.byId().get(candidate.cardId());
            descriptions.put(candidate.cardId(), card.applicableWhen());
        }
        EngineScores scores = decisionEngine.score(task, options, descriptions);
        double[] calibrated = Calibrator.apply(scores.probabilities(), temperature);
        Decision decision = new Decision(
                decisionEngine.name(),
                options,
                scores.probabilities(),
                calibrated,
                temperature,
                scores.reportedConfidence());
        return gate(decision, task, library);
    }

    @Override
    public DecisionOutcome fallback(String task, CardLibrary library, int maxCandidates) {
        // 规则降级路径不使用 noneMargin：RuleFallback 的 None 代表概率被固定为
        // rule-min-score（不是模型判别的结果），归一化后与最佳卡的差值最大只有约 0.14，
        // 永远小于 noneMargin=0.23。若套用 margin，「都不合适」兜底会被强行改成 INJECT。
        Decision decision = ruleFallback.decide(task, library, maxCandidates);
        GateDecision gated = Gate.decide(decision, injectFloor, 0.0);
        return new DecisionOutcome(decision, gated.action(), gated.cardId(), gated.confidence());
    }

    private DecisionOutcome gate(Decision decision, String task, CardLibrary library) {
        GateDecision gated = Gate.decide(decision, injectFloor, noneMargin);
        gated = ruleVeto.apply(task, decision, library, gated);
        return new DecisionOutcome(decision, gated.action(), gated.cardId(), gated.confidence());
    }
}
