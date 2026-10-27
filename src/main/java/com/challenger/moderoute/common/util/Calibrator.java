package com.challenger.moderoute.common.util;

/**
 * 温度缩放。原始 softmax 校准很差（社区实测 ECE 约 0.47），
 * 温度只在验证集上拟合，测试集只用于报告。
 */
public final class Calibrator {

    private Calibrator() {
    }

    public static double[] apply(double[] probabilities, double temperature) {
        if (!(temperature > 0) || Double.isNaN(temperature)) {
            throw new IllegalArgumentException("温度必须为正数: " + temperature);
        }
        double[] out = new double[probabilities.length];
        if (Math.abs(temperature - 1.0) < 1e-12) {
            System.arraycopy(probabilities, 0, out, 0, probabilities.length);
            return out;
        }
        double inverse = 1.0 / temperature;
        double sum = 0;
        for (int i = 0; i < probabilities.length; i++) {
            out[i] = Math.pow(Math.max(probabilities[i], 0), inverse);
            sum += out[i];
        }
        if (sum <= 0) {
            throw new IllegalArgumentException("概率全为 0，无法标定");
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = out[i] / sum;
        }
        return out;
    }
}
