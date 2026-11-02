package com.challenger.modetest.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一条评测任务，直接对应 tasks.jsonl 的一行。
 * constraints / stances / meta 原样保留成 JSON，前端展示时按结构渲染。
 */
public record Task(
        String taskId,
        String domain,
        String prompt,
        List<JsonNode> constraints,
        List<JsonNode> stances,
        List<String> acceptableCards,
        JsonNode meta) {

    public String split() {
        return meta == null ? "" : meta.path("split").asText("");
    }

    public String family() {
        return meta == null ? "" : meta.path("family").asText("");
    }
}
