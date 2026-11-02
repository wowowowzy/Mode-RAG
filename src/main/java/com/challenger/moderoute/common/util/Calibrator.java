package com.challenger.moderoute.common.util;

/**
 * 温度缩放。原始 softmax 校准很差，需要用验证集拟合一个温度再报置信度。
 *
 * 当前 T 由 tools/fit-temperature.py 在 val（63 条）上最小化 ECE 拟合，
 * 测试集只用于报告：test ECE 0.1396 → 0.0852。
 * T<1 表示原始分布偏保守；T>1 表示过度自信。
 *
 * 注意这是保序变换，不改变 argmax，所以调整 T 不影响任何 INJECT / FALLBACK 判定。
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
