package com.challenger.moderoute.entity;

import java.util.List;

public record Decision(
        String engine,
        OptionSet options,
        double[] rawProbabilities,
        double[] probabilities,
        double temperature,
        Double reportedConfidence) {

    public Decision {
        rawProbabilities = rawProbabilities.clone();
        probabilities = probabilities.clone();
        if (rawProbabilities.length != options.size() || probabilities.length != options.size()) {
            throw new IllegalArgumentException("概率数量必须与选项数量一致");
        }
    }

    @Override
    public double[] rawProbabilities() {
        return rawProbabilities.clone();
    }

    @Override
    public double[] probabilities() {
        return probabilities.clone();
    }

    public double[] probabilitiesUnsafe() {
        return probabilities;
    }

    public double[] rawProbabilitiesUnsafe() {
        return rawProbabilities;
    }

    /** 判定为平票的容差。浮点概率在此范围内视为相等。 */
    private static final double TIE_EPSILON = 1e-9;

    /**
     * 取概率最大的选项索引。
     *
     * 平票语义：相等时优先 None。理由见 Gate 的类注释——回退必须是候选池里
     * 一个能赢的选项；若平票总是让字典序最小的卡获胜，None 在结构上永远输。
     */
    public int argmaxIndex() {
        int best = 0;
        for (int i = 1; i < probabilities.length; i++) {
            double current = probabilities[i];
            double bestValue = probabilities[best];
            if (current > bestValue + TIE_EPSILON) {
                best = i;
            } else if (Math.abs(current - bestValue) <= TIE_EPSILON
                    && options.options().get(i).isNone()) {
                best = i;
            }
        }
        return best;
    }

    public char argmaxLetter() {
        return options.options().get(argmaxIndex()).letter();
    }

    public double maxProbability() {
        return probabilities[argmaxIndex()];
    }

    public List<OptionSet.Option> optionList() {
        return options.options();
    }
}
