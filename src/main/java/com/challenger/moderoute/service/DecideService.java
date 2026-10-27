package com.challenger.moderoute.service;

import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.DecisionOutcome;
import com.challenger.moderoute.entity.ScoredCard;

import com.challenger.moderoute.entity.DecisionOutcome;
import com.challenger.moderoute.entity.CardLibrary;
import com.challenger.moderoute.entity.ScoredCard;

import java.util.List;

public interface DecideService {

    /** 精判 + 温度缩放 + 门控。 */
    DecisionOutcome decide(String task, List<ScoredCard> candidates, CardLibrary library);

    /** 规则降级：精判不可用时的确定性替代。 */
    DecisionOutcome fallback(String task, CardLibrary library, int maxCandidates);
}
