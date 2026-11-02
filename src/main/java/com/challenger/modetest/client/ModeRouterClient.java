package com.challenger.modetest.client;

import com.challenger.modetest.config.ModeTestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调 ModeRouter 拿推理骨架。只走 HTTP，不依赖服务端任何代码。
 */
@Component
public class ModeRouterClient {

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final String endpoint;
    private final Duration timeout;

    public ModeRouterClient(ModeTestProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.baseUrl = properties.getRouterUrl().replaceAll("/+$", "");
        this.endpoint = baseUrl + "/v1/enhance";
        this.timeout = Duration.ofMillis(properties.getRouterTimeoutMs());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(3_000))
                .build();
    }

    /** 拉取卡片清单，用于首页按域统计。失败抛 RouterException，由调用方兜住。 */
    public CardsPayload cards() {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/cards"))
                    .timeout(timeout)
                    .GET()
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RouterException("读取卡片清单被中断", e);
        } catch (Exception e) {
            throw new RouterException("读取卡片清单失败: " + describe(e), e);
        }
        if (response.statusCode() != 200) {
            throw new RouterException("读取卡片清单返回 HTTP " + response.statusCode());
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            List<CardEntry> entries = new ArrayList<>();
            for (JsonNode node : root.path("cards")) {
                entries.add(new CardEntry(node.path("id").asText(""), node.path("domain").asText("")));
            }
            return new CardsPayload(root.path("libraryVersion").asText(""), List.copyOf(entries));
        } catch (Exception e) {
            throw new RouterException("解析卡片清单失败: " + e.getMessage(), e);
        }
    }

    public record CardEntry(String id, String domain) {
    }

    public record CardsPayload(String libraryVersion, List<CardEntry> cards) {
    }

    public EnhanceResult enhance(String task) {
        String body;
        try {
            var payload = mapper.createObjectNode();
            payload.put("task", task);
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RouterException("序列化增强请求失败", e);
        }

        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RouterException("调用 ModeRouter 被中断", e);
        } catch (Exception e) {
            throw new RouterException("调用 ModeRouter 失败: " + describe(e), e);
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;

        if (response.statusCode() != 200) {
            throw new RouterException("ModeRouter 返回 HTTP " + response.statusCode() + ": " + snippet(response.body()));
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode meta = root.path("meta");
            return new EnhanceResult(
                    root.path("prompt").asText(""),
                    meta.path("action").asText(""),
                    meta.path("cardId").isNull() ? null : meta.path("cardId").asText(null),
                    meta.path("confidence").asDouble(0),
                    meta.path("libraryVersion").asText(""),
                    root.path("traceId").asText(""),
                    latencyMs);
        } catch (Exception e) {
            throw new RouterException("解析增强响应失败: " + e.getMessage(), e);
        }
    }

    public record EnhanceResult(
            String prompt,
            String action,
            String cardId,
            double confidence,
            String libraryVersion,
            String traceId,
            long latencyMs) {
    }

    public static class RouterException extends RuntimeException {
        public RouterException(String message) {
            super(message);
        }

        public RouterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
