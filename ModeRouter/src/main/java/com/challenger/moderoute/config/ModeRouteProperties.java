package com.challenger.moderoute.config;

import com.challenger.moderoute.common.util.Gate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务配置。所有外部后端都通过配置切换，编排代码不感知具体实现。
 */
@ConfigurationProperties(prefix = "moderoute")
public class ModeRouteProperties {

    /** 卡片种子目录，启动时同步进 SQLite。默认与 application.yml 保持一致。 */
    private String cardsDir = "./data/cards";

    private final Recall recall = new Recall();
    private final Decider decider = new Decider();
    private final Gate gate = new Gate();
    private final Audit audit = new Audit();

    public String getCardsDir() {
        return cardsDir;
    }

    public void setCardsDir(String cardsDir) {
        this.cardsDir = cardsDir;
    }

    public Recall getRecall() {
        return recall;
    }

    public Decider getDecider() {
        return decider;
    }


    public Gate getGate() {
        return gate;
    }

    public Audit getAudit() {
        return audit;
    }

    public static class Recall {

        /** openai（任意 OpenAI 兼容 /v1/embeddings）或 mock。 */
        private String embedding = "openai";
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "text-embedding-3-small";
        private String sqlitePath = "./var/moderoute.db";
        private String extensionPath = "./lib/native/vec0.dll";
        private int timeoutMs = 15_000;
        /** 召回向量是否剔除「不适用于…」负例，默认剔除。 */
        private boolean stripNegativeClause = true;
        /** mock 模式的向量维度，仅用于离线测试。 */
        private int mockDimension = 256;
        /** 单次 embedding 请求的最大条数，网关通常限制在 20 以内。 */
        private int batchSize = 10;

        public String getEmbedding() {
            return embedding;
        }

        public void setEmbedding(String embedding) {
            this.embedding = embedding;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSqlitePath() {
            return sqlitePath;
        }

        public void setSqlitePath(String sqlitePath) {
            this.sqlitePath = sqlitePath;
        }

        public String getExtensionPath() {
            return extensionPath;
        }

        public void setExtensionPath(String extensionPath) {
            this.extensionPath = extensionPath;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public boolean isStripNegativeClause() {
            return stripNegativeClause;
        }

        public void setStripNegativeClause(boolean stripNegativeClause) {
            this.stripNegativeClause = stripNegativeClause;
        }

        public int getMockDimension() {
            return mockDimension;
        }

        public void setMockDimension(int mockDimension) {
            this.mockDimension = mockDimension;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class Decider {

        /** jev-local / jev-api / mock。rule 由 RuleFallback 内部处理，不是可配置的 provider。 */
        private String provider = "jev-local";
        private String baseUrl = "http://127.0.0.1:8765";
        private String apiKey = "";
        /** 必须 ≥ JEV 单次耗时（实测 2–15 s）。默认与 application.yml 保持一致。 */
        private int timeoutMs = 30_000;
        private int connectTimeoutMs = 1_000;
        /**
         * 我们自己的温度缩放系数，只在验证集上拟合；引擎自带温度是另一回事。
         * 默认与 application.yml 一致（0.72）。若 yml 丢失仍能拿到标定后的值，
         * 不会退化成未标定的原始 softmax。
         */
        private double temperature = 0.72;
        /**
         * 规则降级的 None 代表分数：候选卡最高相似度低于该值时回退。
         * 默认与 application.yml 一致（0.04，对应约 22% 回退率）。
         */
        private double ruleMinScore = 0.04;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public double getRuleMinScore() {
            return ruleMinScore;
        }

        public void setRuleMinScore(double ruleMinScore) {
            this.ruleMinScore = ruleMinScore;
        }
    }

    public static class Gate {

        /** 注入下限，默认 0 表示完全由「None 是否胜出」决定，不做阈值工程。 */
        private double injectFloor = 0.0;

        public double getInjectFloor() {
            return injectFloor;
        }

        public void setInjectFloor(double injectFloor) {
            this.injectFloor = injectFloor;
        }
    }

    public static class Audit {

        private boolean enabled = true;
        /** 默认与 application.yml 保持一致。 */
        private String dir = "./var/audit";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }
}
