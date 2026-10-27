package com.challenger.moderoute.config;

import com.challenger.moderoute.decide.DecisionEngine;
import com.challenger.moderoute.decide.MockDecisionEngine;
import com.challenger.moderoute.decide.SystemOneClient;
import com.challenger.moderoute.recall.EmbeddingBackend;
import com.challenger.moderoute.recall.MockEmbeddingModel;
import com.challenger.moderoute.recall.OpenAiCompatibleEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration
public class ApplicationBeans {

    @Bean
    public EmbeddingBackend embeddingBackend(ModeRouteProperties properties, ObjectMapper mapper) {
        ModeRouteProperties.Recall recall = properties.getRecall();
        String kind = recall.getEmbedding() == null ? "" : recall.getEmbedding().toLowerCase(Locale.ROOT);
        if ("mock".equals(kind)) {
            MockEmbeddingModel model = new MockEmbeddingModel(recall.getMockDimension());
            return new EmbeddingBackend(model, model.name());
        }
        if (!"openai".equals(kind)) {
            throw new IllegalStateException("moderoute.recall.embedding 只能是 openai 或 mock，实际为 " + recall.getEmbedding());
        }
        if (recall.getBaseUrl() == null || recall.getBaseUrl().isBlank()) {
            throw new IllegalStateException("moderoute.recall.base-url 未配置（可用 OPENAI_BASE_URL 环境变量）");
        }
        OpenAiCompatibleEmbeddingModel model = new OpenAiCompatibleEmbeddingModel(
                recall.getBaseUrl(), recall.getApiKey(), recall.getModel(), recall.getTimeoutMs(), mapper);
        return new EmbeddingBackend(model, recall.getModel());
    }

    @Bean
    public DecisionEngine decisionEngine(ModeRouteProperties properties, ObjectMapper mapper) {
        ModeRouteProperties.Decider decider = properties.getDecider();
        String provider = decider.getProvider() == null ? "" : decider.getProvider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "mock" -> new MockDecisionEngine();
            case "jev-local", "jev-api" -> new SystemOneClient(
                    provider,
                    decider.getBaseUrl(),
                    decider.getApiKey(),
                    decider.getTimeoutMs(),
                    decider.getConnectTimeoutMs(),
                    mapper);
            default -> throw new IllegalStateException(
                    "moderoute.decider.provider 只能是 jev-local、jev-api 或 mock，实际为 " + decider.getProvider());
        };
    }
}
