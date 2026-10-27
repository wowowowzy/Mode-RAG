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

    public int argmaxIndex() {
        int best = 0;
        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > probabilities[best]) {
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
