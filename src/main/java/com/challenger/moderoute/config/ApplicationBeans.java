package com.challenger.moderoute.config;

import com.challenger.moderoute.decide.DecisionEngine;
import com.challenger.moderoute.decide.MockDecisionEngine;
import com.challenger.moderoute.decide.SystemOneClient;
import com.challenger.moderoute.recall.EmbeddingBackend;
import com.challenger.moderoute.recall.JsonVectorStore;
import com.challenger.moderoute.recall.MockEmbeddingModel;
import com.challenger.moderoute.recall.OpenAiCompatibleEmbeddingModel;
import com.challenger.moderoute.recall.SqliteVectorStore;
import com.challenger.moderoute.recall.VectorCache;
import com.challenger.moderoute.recall.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
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
     * 向量存储默认 SQLite + sqlite-vec。扩展缺失或加载失败时自动退回单文件 JSON，
     * 保证服务在任何机器上都能起来——只是换了持久化方式，接口行为不变。
     */
    @Bean
    public VectorStore vectorStore(ModeRouteProperties properties, VectorCache cache) {
        ModeRouteProperties.Recall recall = properties.getRecall();
        String kind = recall.getStore() == null ? "" : recall.getStore().toLowerCase(Locale.ROOT);
        if ("json".equals(kind)) {
            return new JsonVectorStore(cache);
        }
        if (!"sqlite".equals(kind)) {
            throw new IllegalStateException("moderoute.recall.store 只能是 sqlite 或 json，实际为 " + recall.getStore());
        }
        SqliteVectorStore store = new SqliteVectorStore(
                Path.of(recall.getSqlitePath()), Path.of(recall.getExtensionPath()));
        try {
            store.probe();
            return store;
        } catch (RuntimeException e) {
            log.warn("sqlite-vec 不可用，回退到 JSON 缓存: {}", e.getMessage());
            return new JsonVectorStore(cache);
        }
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
