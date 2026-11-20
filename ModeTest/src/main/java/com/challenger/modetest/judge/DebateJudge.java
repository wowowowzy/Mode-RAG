package com.challenger.modetest.judge;

import com.challenger.modetest.client.ChatGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * debate 任务判定器：LLM-as-judge。
 * 同一任务下把 baseline 与 ours 两份输出放进一个评审 prompt，四项各 0–5 分，
 * ours = B 四项均值，baseline = A 四项均值。
 */
@Component
public class DebateJudge {

    private static final Logger log = LoggerFactory.getLogger(DebateJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TEMPLATE = """
            你是严格的评审。任务原文：
            %s

            【回答A】%s
            【回答B】%s

            请仅输出 JSON：{"A":{"coverage":0-5,"logic":0-5,"structure":0-5,"relevance":0-5},"B":{"coverage":0-5,"logic":0-5,"structure":0-5,"relevance":0-5},"winner":"A|B|tie"}
            不要输出任何额外文字。
            """;

    private final ChatGateway chatGateway;

    public DebateJudge(ChatGateway chatGateway) {
        this.chatGateway = chatGateway;
    }

    /** score 为 null 表示无法评审（调用失败 / 解析失败 / 输出为空）。 */
    public record Result(Double baselineScore, Double oursScore, String winner, String detail) {
    }

    public Result judge(String taskPrompt, String baseline, String ours) {
        if (baseline == null || baseline.isBlank() || ours == null || ours.isBlank()) {
            return new Result(null, null, "", "双臂输出为空，无法评审");
        }
        String prompt = String.format(TEMPLATE, taskPrompt, baseline, ours);
        String text;
        try {
            text = chatGateway.answer(null, prompt).text();
        } catch (RuntimeException e) {
            log.warn("debate 评审调用失败: {}", e.getMessage());
            return new Result(null, null, "", "LLM-judge 调用失败: " + e.getMessage());
        }
        JsonNode root = readJson(text);
        if (root == null) {
            return new Result(null, null, "", "LLM-judge 解析失败: " + snippet(text));
        }
        Double a = mean(root.path("A"));
        Double b = mean(root.path("B"));
        String winner = root.path("winner").asText("");
        if (a == null || b == null) {
            return new Result(null, null, winner, "LLM-judge 评分字段缺失: " + snippet(text));
        }
        return new Result(a, b, winner, "");
    }

    private static Double mean(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String[] keys = {"coverage", "logic", "structure", "relevance"};
        double sum = 0;
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isNumber()) {
                return null;
            }
            sum += value.asDouble();
        }
        return sum / keys.length;
    }

    /** 容忍 ```json 围栏与前后废话，截取第一个 JSON 对象。 */
    private static JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
