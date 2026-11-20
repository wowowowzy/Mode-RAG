package com.challenger.modetest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modetest")
public class ModeTestProperties {

    private String routerUrl = "http://127.0.0.1:18080";
    private int routerTimeoutMs = 30_000;
    private String tasksFile = "./data/tasks/tasks.jsonl";
    private String sqlitePath = "./var/modetest.db";
    private TopK topk = new TopK();

    /**
     * 按域给召回候选池大小。实测（2026-11-19，库版本 0be0e650140a）：
     * debate 需要更多候选（topK 8→16 召回 62%→82%），但 planning 候选变多反而稀释
     * JEV 概率（精判 63%→52%），所以两域必须分开取值。
     */
    public static class TopK {
        private int planning = 8;
        private int debate = 16;
        private int general = 8;

        public int forDomain(String domain) {
            if (domain == null) {
                return planning;
            }
            return switch (domain) {
                case "debate" -> debate;
                case "general" -> general;
                default -> planning;
            };
        }

        public int getPlanning() {
            return planning;
        }

        public void setPlanning(int planning) {
            this.planning = planning;
        }

        public int getDebate() {
            return debate;
        }

        public void setDebate(int debate) {
            this.debate = debate;
        }

        public int getGeneral() {
            return general;
        }

        public void setGeneral(int general) {
            this.general = general;
        }
    }

    public TopK getTopk() {
        return topk;
    }

    public void setTopk(TopK topk) {
        this.topk = topk;
    }

    public String getRouterUrl() {
        return routerUrl;
    }

    public void setRouterUrl(String routerUrl) {
        this.routerUrl = routerUrl;
    }

    public int getRouterTimeoutMs() {
        return routerTimeoutMs;
    }

    public void setRouterTimeoutMs(int routerTimeoutMs) {
        this.routerTimeoutMs = routerTimeoutMs;
    }

    public String getTasksFile() {
        return tasksFile;
    }

    public void setTasksFile(String tasksFile) {
        this.tasksFile = tasksFile;
    }

    public String getSqlitePath() {
        return sqlitePath;
    }

    public void setSqlitePath(String sqlitePath) {
        this.sqlitePath = sqlitePath;
    }
}
