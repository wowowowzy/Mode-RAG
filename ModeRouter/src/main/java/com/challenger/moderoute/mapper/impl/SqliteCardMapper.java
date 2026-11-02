package com.challenger.moderoute.mapper.impl;

import com.challenger.moderoute.config.ModeRouteProperties;
import com.challenger.moderoute.entity.Card;
import com.challenger.moderoute.entity.enums.CardDomain;
import com.challenger.moderoute.mapper.CardMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 模式卡的 SQLite 存储。
 *
 * 卡片正文存正常表，数组字段以 JSON 文本存；写入是一次事务内的整批替换，
 * 读取按 ordinal 还原，保证「同一库版本 → 同一顺序 → 同一快照」。
 */
public class SqliteCardMapper implements CardMapper, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteCardMapper.class);

    private final Path dbPath;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    private Connection connection;

    public SqliteCardMapper(ModeRouteProperties properties, ObjectMapper mapper) {
        this.dbPath = Path.of(properties.getRecall().getSqlitePath()).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public Optional<String> loadedVersion() {
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(
                    "SELECT library_version FROM card_library WHERE id = 1")) {
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
                }
            } catch (Exception e) {
                log.warn("读取卡片库版本失败，按未导入处理: {}", e.getMessage());
                return Optional.empty();
            }
        }
    }

    @Override
    public void replaceAll(String libraryVersion, List<Card> cards) {
        synchronized (lock) {
            try {
                Connection connection = connection();
                connection.setAutoCommit(false);
                try {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("DELETE FROM card");
                        statement.executeUpdate("DELETE FROM card_library");
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO card (card_id, domain, name, applicable_when, steps, failure_modes,"
                                    + " exit_conditions, output_contract, ordinal) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        for (int i = 0; i < cards.size(); i++) {
                            Card card = cards.get(i);
                            insert.setString(1, card.id());
                            insert.setString(2, card.domain().key());
                            insert.setString(3, card.name());
                            insert.setString(4, card.applicableWhen());
                            insert.setString(5, toJson(card.steps()));
                            insert.setString(6, toJson(card.failureModes()));
                            insert.setString(7, toJson(card.exitConditions()));
                            insert.setString(8, card.outputContract());
                            insert.setInt(9, i);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    try (PreparedStatement meta = connection.prepareStatement(
                            "INSERT INTO card_library (id, library_version, card_count, imported_at) VALUES (1, ?, ?, ?)")) {
                        meta.setString(1, libraryVersion);
                        meta.setInt(2, cards.size());
                        meta.setString(3, Instant.now().toString());
                        meta.executeUpdate();
                    }
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                throw new IllegalStateException("写入模式卡失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<Card> loadAll() {
        synchronized (lock) {
            try (PreparedStatement statement = connection().prepareStatement(
                    "SELECT card_id, domain, name, applicable_when, steps, failure_modes,"
                            + " exit_conditions, output_contract FROM card ORDER BY ordinal ASC")) {
                try (ResultSet rows = statement.executeQuery()) {
                    List<Card> cards = new ArrayList<>();
                    while (rows.next()) {
                        cards.add(new Card(
                                rows.getString("card_id"),
                                CardDomain.fromKey(rows.getString("domain")),
                                rows.getString("name"),
                                rows.getString("applicable_when"),
                                fromJson(rows.getString("steps")),
                                fromJson(rows.getString("failure_modes")),
                                fromJson(rows.getString("exit_conditions")),
                                rows.getString("output_contract")));
                    }
                    return List.copyOf(cards);
                }
            } catch (Exception e) {
                throw new IllegalStateException("读取模式卡失败: " + e.getMessage(), e);
            }
        }
    }

    private String toJson(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("序列化卡片字段失败", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("解析卡片字段失败: " + json, e);
        }
    }

    private Connection connection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("创建数据库目录失败: " + e.getMessage(), e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5_000);
        Connection created = DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());
        try (Statement statement = created.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS card ("
                    + "card_id TEXT PRIMARY KEY,"
                    + "domain TEXT NOT NULL,"
                    + "name TEXT NOT NULL,"
                    + "applicable_when TEXT NOT NULL,"
                    + "steps TEXT NOT NULL,"
                    + "failure_modes TEXT NOT NULL,"
                    + "exit_conditions TEXT NOT NULL,"
                    + "output_contract TEXT NOT NULL,"
                    + "ordinal INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS card_library ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1),"
                    + "library_version TEXT NOT NULL,"
                    + "card_count INTEGER NOT NULL,"
                    + "imported_at TEXT NOT NULL)");
        } catch (SQLException e) {
            try {
                created.close();
            } catch (SQLException ignored) {
                // 保留原始错误
            }
            throw e;
        }
        connection = created;
        log.info("卡片库已打开: {}（WAL）", dbPath);
        return connection;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("关闭卡片库失败: {}", e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    /** 供测试与排障使用：当前连接是否已建立。 */
    public boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

}
