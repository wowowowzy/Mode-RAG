package com.challenger.modetest.repository;

import com.challenger.modetest.config.ModeTestProperties;
import com.challenger.modetest.domain.TestRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 测试记录存储。SQLite + WAL，单连接串行访问。
 */
@Repository
public class RunRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunRepository.class);

    private static final String COLUMNS = "id, task_id, domain, created_at, action, card_id, confidence,"
            + " library_version, router_ms, baseline_ms, ours_ms, baseline_prompt_tokens, ours_prompt_tokens,"
            + " completion_tokens";

    private final Path dbPath;
    private Connection connection;

    public RunRepository(ModeTestProperties properties) {
        this.dbPath = Path.of(properties.getSqlitePath()).toAbsolutePath().normalize();
    }

    public TestRun save(TestRun run) {
        synchronized (this) {
            try {
                Connection connection = connection();
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO test_run (task_id, domain, created_at, action, card_id, confidence,"
                                + " library_version, router_ms, baseline_ms, ours_ms, baseline_prompt_tokens,"
                                + " ours_prompt_tokens, completion_tokens, baseline_text, ours_text, error)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, run.taskId());
                    statement.setString(2, run.domain());
                    statement.setString(3, run.createdAt());
                    statement.setString(4, run.action());
                    statement.setString(5, run.cardId());
                    statement.setDouble(6, run.confidence());
                    statement.setString(7, run.libraryVersion());
                    statement.setLong(8, run.routerMs());
                    statement.setLong(9, run.baselineMs());
                    statement.setLong(10, run.oursMs());
                    setInteger(statement, 11, run.baselinePromptTokens());
                    setInteger(statement, 12, run.oursPromptTokens());
                    setInteger(statement, 13, run.completionTokens());
                    statement.setString(14, run.baselineText());
                    statement.setString(15, run.oursText());
                    statement.setString(16, run.error());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (keys.next()) {
                            return withId(run, keys.getLong(1));
                        }
                    }
                }
                return run;
            } catch (Exception e) {
                throw new IllegalStateException("保存测试记录失败: " + e.getMessage(), e);
            }
        }
    }

    /** 列表不返回正文，避免一次拉回几百份长文本。 */
    public List<TestRun> list(int limit) {
        synchronized (this) {
            try (PreparedStatement statement = connection().prepareStatement(
                    "SELECT " + COLUMNS + " FROM test_run ORDER BY id DESC LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<TestRun> runs = new ArrayList<>();
                    while (rows.next()) {
                        runs.add(map(rows, false));
                    }
                    return runs;
                }
            } catch (Exception e) {
                throw new IllegalStateException("读取测试记录失败: " + e.getMessage(), e);
            }
        }
    }

    public Optional<TestRun> find(long id) {
        synchronized (this) {
            try (PreparedStatement statement = connection().prepareStatement(
                    "SELECT " + COLUMNS + ", baseline_text, ours_text, error FROM test_run WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(map(rows, true)) : Optional.empty();
                }
            } catch (Exception e) {
                throw new IllegalStateException("读取测试记录失败: " + e.getMessage(), e);
            }
        }
    }

    public long count() {
        synchronized (this) {
            try (Statement statement = connection().createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM test_run")) {
                return rows.next() ? rows.getLong(1) : 0;
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private Connection connection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new SQLException("创建数据库目录失败: " + e.getMessage(), e);
        }
        Connection created = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement statement = created.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS test_run ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "task_id TEXT NOT NULL,"
                    + "domain TEXT NOT NULL,"
                    + "created_at TEXT NOT NULL,"
                    + "action TEXT,"
                    + "card_id TEXT,"
                    + "confidence REAL NOT NULL DEFAULT 0,"
                    + "library_version TEXT,"
                    + "router_ms INTEGER NOT NULL DEFAULT 0,"
                    + "baseline_ms INTEGER NOT NULL DEFAULT 0,"
                    + "ours_ms INTEGER NOT NULL DEFAULT 0,"
                    + "baseline_prompt_tokens INTEGER,"
                    + "ours_prompt_tokens INTEGER,"
                    + "completion_tokens INTEGER,"
                    + "baseline_text TEXT,"
                    + "ours_text TEXT,"
                    + "error TEXT)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_run_task ON test_run(task_id)");
        } catch (SQLException e) {
            try {
                created.close();
            } catch (SQLException ignored) {
                // 保留原始错误
            }
            throw e;
        }
        connection = created;
        log.info("测试记录库已打开: {}（WAL）", dbPath);
        return connection;
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer getInteger(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static TestRun map(ResultSet rows, boolean withText) throws SQLException {
        return new TestRun(
                rows.getLong("id"),
                rows.getString("task_id"),
                rows.getString("domain"),
                rows.getString("created_at"),
                rows.getString("action"),
                rows.getString("card_id"),
                rows.getDouble("confidence"),
                rows.getString("library_version"),
                rows.getLong("router_ms"),
                rows.getLong("baseline_ms"),
                rows.getLong("ours_ms"),
                getInteger(rows, "baseline_prompt_tokens"),
                getInteger(rows, "ours_prompt_tokens"),
                getInteger(rows, "completion_tokens"),
                withText ? rows.getString("baseline_text") : null,
                withText ? rows.getString("ours_text") : null,
                withText ? rows.getString("error") : null);
    }

    private static TestRun withId(TestRun run, long id) {
        return new TestRun(id, run.taskId(), run.domain(), run.createdAt(), run.action(), run.cardId(),
                run.confidence(), run.libraryVersion(), run.routerMs(), run.baselineMs(), run.oursMs(),
                run.baselinePromptTokens(), run.oursPromptTokens(), run.completionTokens(),
                run.baselineText(), run.oursText(), run.error());
    }

    @Override
    public void close() {
        synchronized (this) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("关闭测试记录库失败: {}", e.getMessage());
            } finally {
                connection = null;
            }
        }
    }
}
