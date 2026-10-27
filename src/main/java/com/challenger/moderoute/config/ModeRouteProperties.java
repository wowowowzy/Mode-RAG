package com.challenger.moderoute.config;

import com.challenger.moderoute.common.util.Gate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务配置。所有外部后端都通过配置切换，编排代码不感知具体实现。
 */
@ConfigurationProperties(prefix = "moderoute")
public class ModeRouteProperties {

    /** 卡片目录，启动期一次性加载为只读快照。 */
    private String cardsDir = "./cards";

    /** 实验调试参数开关，生产默认关闭。 */
    private boolean overrideEnabled = false;

    private final Recall recall = new Recall();
    private final Decider decider = new Decider();
    private final Render render = new Render();
    private final Gate gate = new Gate();
    private final Audit audit = new Audit();

    public String getCardsDir() {
        return cardsDir;
    }

    public void setCardsDir(String cardsDir) {
        this.cardsDir = cardsDir;
    }

    public boolean isOverrideEnabled() {
        return overrideEnabled;
    }

    public void setOverrideEnabled(boolean overrideEnabled) {
        this.overrideEnabled = overrideEnabled;
    }

    public Recall getRecall() {
        return recall;
    }

    public Decider getDecider() {
        return decider;
    }

    public Render getRender() {
        return render;
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

        /** jev-local / jev-api / mock / rule。 */
        private String provider = "jev-local";
        private String baseUrl = "http://127.0.0.1:8765";
        private String apiKey = "";
        private int timeoutMs = 5_000;
        private int connectTimeoutMs = 1_000;
        /** 我们自己的温度缩放系数，只在验证集上拟合；引擎自带温度是另一回事。 */
        private double temperature = 1.0;
        /** 规则降级时，最低词面相似度；低于该值视为都不合适。 */
        private double ruleMinScore = 0.02;

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

    public static class Render {

        private int defaultBudgetTokens = 400;
        private int minBudgetTokens = 64;
        private int maxBudgetTokens = 2_000;

        public int getDefaultBudgetTokens() {
            return defaultBudgetTokens;
        }

        public void setDefaultBudgetTokens(int defaultBudgetTokens) {
            this.defaultBudgetTokens = defaultBudgetTokens;
        }

        public int getMinBudgetTokens() {
            return minBudgetTokens;
        }

        public void setMinBudgetTokens(int minBudgetTokens) {
            this.minBudgetTokens = minBudgetTokens;
        }

        public int getMaxBudgetTokens() {
            return maxBudgetTokens;
        }

        public void setMaxBudgetTokens(int maxBudgetTokens) {
            this.maxBudgetTokens = maxBudgetTokens;
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
        private String dir = "./runs/audit";

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
