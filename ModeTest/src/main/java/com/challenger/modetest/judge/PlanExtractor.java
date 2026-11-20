package com.challenger.modetest.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 从模型输出中提取 plan_v1 的 assignments 数组。容忍 ```json 代码块与前后废话。 */
public final class PlanExtractor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Assignment(String order, String line, int day, double startHour, double hours) {}

    private PlanExtractor() {}

    public static Optional<List<Assignment>> extract(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String json = sliceJson(text);
        if (json == null) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.path("assignments");
            if (!arr.isArray()) return Optional.empty();
            List<Assignment> out = new ArrayList<>();
            for (JsonNode n : arr) {
                out.add(new Assignment(
                        n.path("order").asText(""),
                        n.path("line").asText(""),
                        n.path("day").asInt(-1),
                        n.path("startHour").asDouble(0),
                        n.path("hours").asDouble(0)));
            }
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 截取第一个形如 { ... "assignments" ... } 的 JSON 块。 */
    private static String sliceJson(String text) {
        String body = text;
        int fence = body.indexOf("```");
        if (fence >= 0) {
            int start = body.indexOf('{', fence);
            int endFence = body.indexOf("```", fence + 3);
            int end = body.lastIndexOf('}', endFence > 0 ? endFence : body.length());
            if (start >= 0 && end > start) return body.substring(start, end + 1);
        }
        int key = body.indexOf("\"assignments\"");
        if (key < 0) return null;
        int start = body.lastIndexOf('{', key);
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return body.substring(start, end + 1);
    }
}
