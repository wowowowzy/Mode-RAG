package com.challenger.moderoute.recall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 /v1/embeddings 的最小实现。
 * 之所以自己写传输层而不用 provider 模块：provider 模块会把 chat/completion 类一起带上，
 * 那样「不碰执行模型」就只能靠承诺，不能靠依赖树。
 */
public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private volatile int dimension = 0;

    public OpenAiCompatibleEmbeddingModel(String baseUrl, String apiKey, String model, int timeoutMs, ObjectMapper mapper) {
        this.mapper = mapper;
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/v1/embeddings";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 5_000)))
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        var payload = mapper.createObjectNode();
        payload.put("model", model);
        var arr = payload.putArray("input");
        inputs.forEach(arr::add);

        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 embedding 请求失败", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException("embedding 返回 HTTP " + response.statusCode()
                    + ": " + snippet(response.body()));
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("embedding 响应缺少 data: " + snippet(response.body()));
            }
            List<Embedding> embeddings = new ArrayList<>(inputs.size());
            for (int i = 0; i < data.size(); i++) {
                JsonNode item = data.get(i);
                int index = item.path("index").asInt(i);
                JsonNode vectorNode = item.path("embedding");
                float[] vector = new float[vectorNode.size()];
                for (int j = 0; j < vector.length; j++) {
                    vector[j] = (float) vectorNode.get(j).asDouble();
                }
                embeddings.add(new Embedding(vector, index));
            }
            embeddings.sort((a, b) -> Integer.compare(a.getIndex(), b.getIndex()));
            dimension = embeddings.get(0).getOutput().length;
            return new EmbeddingResponse(embeddings);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 embedding 响应失败: " + e.getMessage(), e);
        }
    }

    @Override
    public float[] embed(Document document) {
        EmbeddingResponse response = call(new EmbeddingRequest(
                List.of(document.getText()), null));
        return response.getResults().get(0).getOutput();
    }

    @Override
    public int dimensions() {
        return dimension;
    }

    public Map<String, Object> describe() {
        return Map.of("model", model, "endpoint", endpoint);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
