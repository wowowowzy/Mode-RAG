package com.challenger.modetest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modetest")
public class ModeTestProperties {

    private String routerUrl = "http://127.0.0.1:18080";
    private int routerTimeoutMs = 30_000;
    private String tasksFile = "./data/tasks/tasks.jsonl";
    private String sqlitePath = "./var/modetest.db";

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
