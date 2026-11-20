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

    /**
     * 召回候选池大小由调用方按域给（见 ModeTestProperties.TopK）。
     * 实测 topK 对两域的作用相反：debate 需要更大候选池，planning 反而会被稀释。
     */
    private static final int DEFAULT_TOP_K = 8;

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

    /** 取指定卡片的完整骨架（ModeRouter 只读端点，供 oracle 臂使用）。 */
    public SkeletonResult cardSkeleton(String cardId) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v1/cards/" + cardId + "/skeleton"))
                    .timeout(timeout).GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RouterException("读取卡片骨架被中断", e);
        } catch (Exception e) {
            throw new RouterException("读取卡片骨架失败: " + describe(e), e);
        }
        if (response.statusCode() != 200) {
            throw new RouterException("读取卡片骨架返回 HTTP " + response.statusCode()
                    + ": " + snippet(response.body()));
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            return new SkeletonResult(
                    root.path("cardId").asText(""),
                    root.path("domain").asText(""),
                    root.path("prompt").asText(""));
        } catch (Exception e) {
            throw new RouterException("解析卡片骨架失败: " + e.getMessage(), e);
        }
    }

    public record SkeletonResult(String cardId, String domain, String prompt) {
    }

    public EnhanceResult enhance(String task) {
        return enhance(task, DEFAULT_TOP_K);
    }

    public EnhanceResult enhance(String task, int topK) {
        return enhance(task, topK, null);
    }

    /**
     * domain 与 topK 同为召回范围参数；null / 空表示不限制召回域。
     */
    public EnhanceResult enhance(String task, int topK, String domain) {
        String body;
        try {
            var payload = mapper.createObjectNode();
            payload.put("task", task);
            var options = payload.putObject("options");
            options.put("topK", topK);
            if (domain != null && !domain.isBlank()) {
                options.put("domain", domain);
            }
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
