package com.challenger.moderoute.client;

import com.challenger.moderoute.entity.EngineScores;
import com.challenger.moderoute.entity.OptionSet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 离线精判替身：概率由 (任务, 字母) 的哈希决定，完全确定、与网络无关。
 * 用来在零外部依赖下验证门控、渲染、审计与接口行为，不代表真实精度。
 */
public final class MockDecisionEngine implements DecisionEngine {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public EngineScores score(String task, OptionSet options, Map<String, String> descriptions) {
        double[] weights = new double[options.size()];
        double sum = 0;
        for (int i = 0; i < options.size(); i++) {
            double unit = unitHash(task + "|" + options.options().get(i).letter());
            weights[i] = 0.02 + unit * unit;
            sum += weights[i];
        }
        for (int i = 0; i < weights.length; i++) {
            weights[i] = weights[i] / sum;
        }
        return new EngineScores(weights, null);
    }

    private static double unitHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (digest[i] & 0xFFL);
            }
            return (bits >>> 11) / (double) (1L << 53);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
