package com.challenger.moderoute.decide;

/**
 * 引擎给出的原始概率。reportedConfidence 是引擎自己的口径（TypeSafe 公式），
 * 与我们要报告的标定后置信度不是一回事，两者都要进审计。
 */
public record EngineScores(double[] probabilities, Double reportedConfidence) {

    public EngineScores {
        probabilities = probabilities.clone();
    }

    @Override
    public double[] probabilities() {
        return probabilities.clone();
    }
}
