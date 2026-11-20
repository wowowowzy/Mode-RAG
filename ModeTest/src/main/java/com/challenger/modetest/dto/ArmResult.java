package com.challenger.modetest.dto;

/**
 * 三臂实验单条结果。统一输出契约：三个臂写同一套字段，便于盲评去掉 arm 标签后打分。
 */
public record ArmResult(
        String taskId,
        String domain,
        String split,
        String arm,
        String action,
        String cardId,
        double confidence,
        String libraryVersion,
        long routerMs,
        long modelMs,
        Integer promptTokens,
        Integer completionTokens,
        String outputText,
        Double score,
        Integer passed,
        Integer total,
        Boolean parsed,
        String judgeDetail) {
}
