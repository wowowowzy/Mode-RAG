package com.challenger.moderoute.config;

import com.challenger.moderoute.client.DecisionEngine;
import com.challenger.moderoute.client.EmbeddingBackend;
import com.challenger.moderoute.client.MockDecisionEngine;
import com.challenger.moderoute.client.MockEmbeddingModel;
import com.challenger.moderoute.client.OpenAiCompatibleEmbeddingModel;
import com.challenger.moderoute.client.SystemOneClient;
import com.challenger.moderoute.mapper.CardMapper;
import com.challenger.moderoute.mapper.VectorMapper;
import com.challenger.moderoute.mapper.impl.SqliteCardMapper;
import com.challenger.moderoute.mapper.impl.SqliteVectorMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Locale;

@Configuration
public class ApplicationBeans {

    private static final Logger log = LoggerFactory.getLogger(ApplicationBeans.class);

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

    /**
     * 向量存储：SQLite + sqlite-vec。扩展缺失或加载失败时启动直接失败，
     * 不静默退回其它实现——运行路径只有一条。
     */
    @Bean
    public VectorMapper vectorStore(ModeRouteProperties properties) {
        ModeRouteProperties.Recall recall = properties.getRecall();
        SqliteVectorMapper store = new SqliteVectorMapper(
                Path.of(recall.getSqlitePath()), Path.of(recall.getExtensionPath()));
        try {
            store.probe();
        } catch (RuntimeException e) {
            throw new IllegalStateException("向量库初始化失败: " + e.getMessage()
                    + "。请确认 lib/native/vec0.dll 存在（见 README「sqlite-vec 扩展」）", e);
        }
        return store;
    }

    @Bean
    public CardMapper cardMapper(ModeRouteProperties properties, ObjectMapper mapper) {
        return new SqliteCardMapper(properties, mapper);
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
