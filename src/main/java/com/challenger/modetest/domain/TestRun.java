package com.challenger.modetest.domain;

/**
 * 一次双臂对比的结果落库形态。
 */
public record TestRun(
        long id,
        String taskId,
        String domain,
        String createdAt,
        String action,
        String cardId,
        double confidence,
        String libraryVersion,
        long routerMs,
        long baselineMs,
        long oursMs,
        Integer baselinePromptTokens,
        Integer oursPromptTokens,
        Integer completionTokens,
        String baselineText,
        String oursText,
        String error) {

    public boolean injected() {
        return "INJECT".equals(action) && cardId != null;
    }
}
