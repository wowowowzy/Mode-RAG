package com.challenger.modetest.domain;

/**
 * 一次双臂对比的结果落库形态。
 * 判定字段（*_score / *_passed / judge_detail）由 /api/runs/{id}/judge 回填，未判定时为 null。
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
        String error,
        Double baselineScore,
        Double oursScore,
        Integer baselinePassed,
        Integer oursPassed,
        String judgeDetail) {

    /** 兼容双臂落库：判定字段先置空，等待 /judge 回填。 */
    public TestRun(long id, String taskId, String domain, String createdAt, String action, String cardId,
                   double confidence, String libraryVersion, long routerMs, long baselineMs, long oursMs,
                   Integer baselinePromptTokens, Integer oursPromptTokens, Integer completionTokens,
                   String baselineText, String oursText, String error) {
        this(id, taskId, domain, createdAt, action, cardId, confidence, libraryVersion, routerMs,
                baselineMs, oursMs, baselinePromptTokens, oursPromptTokens, completionTokens,
                baselineText, oursText, error, null, null, null, null, null);
    }

    public boolean injected() {
        return "INJECT".equals(action) && cardId != null;
    }
}
