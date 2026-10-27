package com.challenger.moderoute.mapper.impl;

import com.challenger.moderoute.common.util.LibraryVersion;
import com.challenger.moderoute.entity.ScoredCard;
import com.challenger.moderoute.mapper.VectorMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

/**
 * 向量存 SQLite（WAL）+ sqlite-vec 的余弦距离函数。
 *
 * 设计取舍：
 * - 不用 vec0 虚拟表。vec0 建表即定死维度，换 embedding 模型就要迁移；
 *   这里用普通表存 float32 BLOB，距离交给 vec_distance_cosine()，维度由应用校验。
 * - 版本 / 模型 / 卡片集合 / 维度 任一不匹配即视为未就绪，整批重算，
 *   保证「删掉数据库重启，行为不变」。
 * - 单连接 + 串行访问：48 行的查询在百微秒级，连接池带来的复杂度不划算。
 */
public final class SqliteVectorMapper implements VectorMapper, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteVectorMapper.class);

    private final Path dbPath;
    private final Path extensionPath;
    private final Object lock = new Object();

    private Connection connection;

    public SqliteVectorMapper(Path dbPath, Path extensionPath) {
        this.dbPath = dbPath.toAbsolutePath().normalize();
        this.extensionPath = extensionPath.toAbsolutePath().normalize();
    }

    @Override
    public String kind() {
        return "sqlite";
    }

    /** 启动期自检：打开连接并加载 sqlite-vec，失败直接抛出，由工厂决定是否回退。 */
    public void probe() {
        synchronized (lock) {
            try {
                connection();
            } catch (Exception e) {
                throw new IllegalStateException("sqlite-vec 不可用: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean ready(String libraryVersion, String model, List<String> cardIds) {
        synchronized (lock) {
            try {
                Connection connection = connection();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT card_ids, card_count, dimension FROM vector_meta WHERE library_version = ? AND model = ?")) {
                    statement.setString(1, libraryVersion);
                    statement.setString(2, model);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            return false;
                        }
                        if (rows.getInt("card_count") != cardIds.size()) {
                            return false;
                        }
                        if (rows.getInt("dimension") <= 0) {
                            return false;
                        }
                        if (!String.join(",", cardIds).equals(rows.getString("card_ids"))) {
                            return false;
                        }
                    }
                }
                return count(libraryVersion, model) == cardIds.size();
            } catch (Exception e) {
                log.warn("向量库不可用，按未就绪处理: {}", e.getMessage());
                return false;
            }
        }
    }

    @Override
    public List<ScoredCard> topK(String libraryVersion, String model, float[] query, int limit) {
        synchronized (lock) {
            try {
                Connection connection = connection();
                int dimension = dimension(libraryVersion, model);
                if (dimension <= 0) {
                    throw new IllegalStateException("向量维度缺失");
                }
                if (query.length != dimension) {
                    throw new IllegalStateException("查询维度 " + query.length + " 与存储维度 " + dimension + " 不一致");
                }
                String sql = "SELECT card_id, vec_distance_cosine(embedding, ?) AS distance "
                        + "FROM vector WHERE library_version = ? AND model = ? "
                        + "ORDER BY distance ASC, card_id ASC LIMIT ?";
                List<ScoredCard> scored = new ArrayList<>(limit);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setBytes(1, toBlob(query));
                    statement.setString(2, libraryVersion);
                    statement.setString(3, model);
                    statement.setInt(4, limit);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            double score = 1.0 - rows.getDouble("distance");
                            scored.add(new ScoredCard(rows.getString("card_id"), round6(score)));
                        }
                    }
                }
                return List.copyOf(scored);
            } catch (SQLException e) {
                throw new IllegalStateException("向量检索失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void save(String libraryVersion, String model, List<String> cardIds, List<float[]> vectors) {
        if (cardIds.size() != vectors.size()) {
            throw new IllegalArgumentException("卡片数与向量数不一致");
        }
        synchronized (lock) {
            try {
                Connection connection = connection();
                connection.setAutoCommit(false);
                try {
                    // 只保留当前库快照：旧版本的向量没有消费方，留着只会占空间。
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("DELETE FROM vector");
                        statement.executeUpdate("DELETE FROM vector_meta");
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO vector (library_version, model, card_id, embedding) VALUES (?, ?, ?, ?)")) {
                        for (int i = 0; i < cardIds.size(); i++) {
                            insert.setString(1, libraryVersion);
                            insert.setString(2, model);
                            insert.setString(3, cardIds.get(i));
                            insert.setBytes(4, toBlob(vectors.get(i)));
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    try (PreparedStatement meta = connection.prepareStatement(
                            "INSERT INTO vector_meta (library_version, model, dimension, card_count, card_ids, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                        meta.setString(1, libraryVersion);
                        meta.setString(2, model);
                        meta.setInt(3, vectors.get(0).length);
                        meta.setInt(4, cardIds.size());
                        meta.setString(5, String.join(",", cardIds));
                        meta.setString(6, Instant.now().toString());
                        meta.executeUpdate();
                    }
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                log.warn("写向量库失败（不影响服务，下次启动会重算）: {}", e.getMessage());
                return;
            }
            checkpoint();
        }
    }

    private void checkpoint() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            log.debug("wal_checkpoint 失败（不影响正确性）: {}", e.getMessage());
        }
    }

    private int count(String libraryVersion, String model) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM vector WHERE library_version = ? AND model = ?")) {
            statement.setString(1, libraryVersion);
            statement.setString(2, model);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private int dimension(String libraryVersion, String model) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT dimension FROM vector_meta WHERE library_version = ? AND model = ?")) {
            statement.setString(1, libraryVersion);
            statement.setString(2, model);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    private Connection connection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("创建向量库目录失败: " + e.getMessage(), e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5_000);
        Connection created = DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());
        try {
            loadExtension(created);
            initSchema(created);
        } catch (SQLException e) {
            try {
                created.close();
            } catch (SQLException ignored) {
                // 关闭失败不覆盖原始错误
            }
            throw e;
        }
        connection = created;
        log.info("向量库已打开: {}（WAL），sqlite-vec {}", dbPath, version(created));
        return connection;
    }

    private void loadExtension(Connection connection) throws SQLException {
        if (!Files.isRegularFile(extensionPath)) {
            throw new SQLException("缺少 sqlite-vec 扩展: " + extensionPath
                    + "（执行 tools/fetch-sqlite-vec.ps1 下载）");
        }
        String path = extensionPath.toString().replace('\\', '/').replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT load_extension('" + path + "')");
        }
    }

    private String version(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT vec_version()")) {
            return rows.next() ? rows.getString(1) : "unknown";
        } catch (SQLException e) {
            return "unknown";
        }
    }

    private void initSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS vector_meta ("
                    + "library_version TEXT NOT NULL,"
                    + "model TEXT NOT NULL,"
                    + "dimension INTEGER NOT NULL,"
                    + "card_count INTEGER NOT NULL,"
                    + "card_ids TEXT NOT NULL,"
                    + "created_at TEXT NOT NULL,"
                    + "PRIMARY KEY (library_version, model))");
            statement.execute("CREATE TABLE IF NOT EXISTS vector ("
                    + "library_version TEXT NOT NULL,"
                    + "model TEXT NOT NULL,"
                    + "card_id TEXT NOT NULL,"
                    + "embedding BLOB NOT NULL,"
                    + "PRIMARY KEY (library_version, model, card_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_vector_scope ON vector(library_version, model)");
        }
    }

    /** sqlite-vec 要求 float32 小端连续内存。 */
    private static byte[] toBlob(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
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
                log.debug("关闭向量库失败: {}", e.getMessage());
            } finally {
                connection = null;
            }
        }
    }
}
