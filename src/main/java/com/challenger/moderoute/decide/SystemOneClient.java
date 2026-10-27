package com.challenger.moderoute.decide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * JEV / TypeSafe System One 客户端。
 * 这个接口既不是 OpenAI chat 也不是 OpenAI embeddings 格式，没有现成抽象，手写 HTTP 更直接。
 */
public final class SystemOneClient implements DecisionEngine {

    private final String name;
    private final String url;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public SystemOneClient(String name, String baseUrl, String apiKey, int timeoutMs, int connectTimeoutMs,
                           ObjectMapper mapper) {
        this.name = name;
        this.url = baseUrl.replaceAll("/+$", "") + "/v1/systemone";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public EngineScores score(String task, OptionSet options, Map<String, String> descriptions) {
        var payload = mapper.createObjectNode();
        payload.put("state", StateBuilder.build(task, options, descriptions));

        var question = payload.putObject("questions").putObject("card");
        question.put("type", "choice");
        question.put("instructions", "哪个候选骨架最适配这个任务？");
        var criteria = question.putObject("criteria");
        for (OptionSet.Option option : options.options()) {
            criteria.putNull(String.valueOf(option.letter()));
        }

        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new DecisionException("序列化精判请求失败", e);
        }

        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecisionException("精判调用被中断", e);
        } catch (Exception e) {
            throw new DecisionException("精判调用失败: " + describe(e), e);
        }

        if (response.statusCode() != 200) {
            throw new DecisionException("精判返回 HTTP " + response.statusCode() + ": " + snippet(response.body()));
        }
        return parse(response.body(), options);
    }

    private EngineScores parse(String body, OptionSet options) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new DecisionException("解析精判响应失败: " + e.getMessage(), e);
        }
        JsonNode answer = root.path("answers").path("card");
        if (answer.isMissingNode() || answer.isNull()) {
            throw new DecisionException("精判响应缺少 answers.card: " + snippet(body));
        }
        JsonNode probs = answer.path("probabilities");
        if (!probs.isObject()) {
            throw new DecisionException("精判响应缺少 probabilities: " + snippet(body));
        }

        double[] values = new double[options.size()];
        double sum = 0;
        for (int i = 0; i < options.size(); i++) {
            JsonNode node = probs.get(String.valueOf(options.options().get(i).letter()));
            double value = node == null ? 0 : node.asDouble(0);
            if (value < 0 || Double.isNaN(value)) {
                value = 0;
            }
            values[i] = value;
            sum += value;
        }
        if (sum <= 0) {
            throw new DecisionException("精判概率全为 0: " + snippet(body));
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = values[i] / sum;
        }
        JsonNode confidence = answer.get("confidence");
        Double reported = confidence == null || confidence.isNull() ? null : confidence.asDouble();
        return new EngineScores(values, reported);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }

    static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }
}
