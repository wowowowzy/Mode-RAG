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
    private final double temperature;
    private final double injectFloor;

    public DecideServiceImpl(ModeRouteProperties properties,
                             DecisionEngine decisionEngine,
                             RuleFallback ruleFallback) {
        this.decisionEngine = decisionEngine;
        this.ruleFallback = ruleFallback;
        this.temperature = properties.getDecider().getTemperature();
        this.injectFloor = properties.getGate().getInjectFloor();
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
        return gate(decision);
    }

    @Override
    public DecisionOutcome fallback(String task, CardLibrary library, int maxCandidates) {
        return gate(ruleFallback.decide(task, library, maxCandidates));
    }

    private DecisionOutcome gate(Decision decision) {
        GateDecision gated = Gate.decide(decision, injectFloor);
        return new DecisionOutcome(decision, gated.action(), gated.cardId(), gated.confidence());
    }
}
