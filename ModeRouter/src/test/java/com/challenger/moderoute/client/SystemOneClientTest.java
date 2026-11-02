package com.challenger.moderoute.client;

import com.challenger.moderoute.client.SystemOneClient;
import com.challenger.moderoute.common.DecisionException;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.Decision;
import com.challenger.moderoute.entity.EngineScores;
import com.challenger.moderoute.entity.OptionSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemOneClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> captured = new AtomicReference<>();
    private volatile String responseBody;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void 发送单字母选项并解析概率() {
        responseBody = """
                {"model":"jev-style-0.8b-decision-v3","answers":{"card":{"type":"choice","choice":"B",
                "confidence":0.55,"probabilities":{"A":0.2,"B":0.6,"C":0.2}}},"latency_ms":12.0}
                """;
        SystemOneClient client = new SystemOneClient("jev-local",
                "http://127.0.0.1:" + server.getAddress().getPort(), "", 2000, 500, mapper);

        OptionSet options = OptionSet.ofCardIds(List.of("planning-capacity-first", "planning-window-first"));
        EngineScores scores = client.score("为三条产线排布五张订单",
                options, Map.of("planning-capacity-first", "容量先行", "planning-window-first", "时间窗先行"));

        assertThat(scores.probabilities()).containsExactly(0.2, 0.6, 0.2);
        assertThat(scores.reportedConfidence()).isEqualTo(0.55);

        JsonNode request = read(captured.get());
        assertThat(request.path("questions").path("card").path("type").asText()).isEqualTo("choice");
        assertThat(request.path("questions").path("card").path("criteria").has("A")).isTrue();
        assertThat(request.path("questions").path("card").path("criteria").has("C")).isTrue();
        String state = request.path("state").asText();
        assertThat(state).contains("A. 容量先行").contains("B. 时间窗先行").contains("None");
    }

    @Test
    void 概率缺失时按零处理并重新归一() {
        responseBody = """
                {"answers":{"card":{"type":"choice","choice":"A","probabilities":{"A":3,"B":1}}}}
                """;
        SystemOneClient client = new SystemOneClient("jev-local",
                "http://127.0.0.1:" + server.getAddress().getPort(), "", 2000, 500, mapper);

        OptionSet options = OptionSet.ofCardIds(List.of("a-1", "b-1"));
        EngineScores scores = client.score("任务", options, Map.of("a-1", "甲", "b-1", "乙"));

        assertThat(scores.probabilities()[0]).isEqualTo(0.75);
        assertThat(scores.probabilities()[1]).isEqualTo(0.25);
        assertThat(scores.probabilities()[2]).isEqualTo(0.0);
        assertThat(scores.reportedConfidence()).isNull();
    }

    @Test
    void 非两百响应转为异常由上层降级() {
        server.removeContext("/v1/systemone");
        server.createContext("/v1/systemone", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        SystemOneClient client = new SystemOneClient("jev-local",
                "http://127.0.0.1:" + server.getAddress().getPort(), "", 2000, 500, mapper);

        assertThatThrownBy(() -> client.score("任务", OptionSet.ofCardIds(List.of("a-1")), Map.of("a-1", "甲")))
                .isInstanceOf(DecisionException.class)
                .hasMessageContaining("500");
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
