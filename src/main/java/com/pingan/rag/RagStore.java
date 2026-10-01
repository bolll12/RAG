package com.pingan.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

public final class RagStore {
    private final String url;
    @FunctionalInterface interface Work<T> { T run(Connection db) throws Exception; }
    public RagStore(RagSettings settings) {
        try { Files.createDirectories(Path.of(settings.db()).toAbsolutePath().getParent()); }
        catch (Exception e) { throw new IllegalStateException("无法创建数据目录", e); }
        url = "jdbc:sqlite:" + settings.db();
        read(db -> {
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("CREATE TABLE IF NOT EXISTS documents (id TEXT PRIMARY KEY,collection TEXT NOT NULL,source TEXT NOT NULL,title TEXT NOT NULL,hash TEXT NOT NULL,version INTEGER NOT NULL,text TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(collection,source))");
                s.execute("CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,start INTEGER NOT NULL,end INTEGER NOT NULL,text TEXT NOT NULL,vector TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS traces (id TEXT PRIMARY KEY,created_at TEXT DEFAULT CURRENT_TIMESTAMP,data TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS chunks_document_idx ON chunks(document_id)");
            }
            return null;
        });
        transaction(db -> {
            var previous = one(db, "SELECT value FROM metadata WHERE key='index_signature'");
            var expected = Json.MAPPER.valueToTree(settings.signature());
            if (previous != null && !Json.read((String) previous.get("value")).equals(expected)
                    && !query(db, "SELECT id FROM documents LIMIT 1").isEmpty())
                throw new IllegalArgumentException("索引配置已改变；请使用新的 RAG_DB 并重新导入原文");
            if (previous == null || !Json.read((String) previous.get("value")).equals(expected))
                update(db, "INSERT OR REPLACE INTO metadata VALUES ('index_signature',?)", Json.write(settings.signature()));
            var columns = query(db, "PRAGMA table_info(documents)").stream().map(row -> row.get("name")).toList();
            if (!columns.contains("chunk_strategy")) update(db, "ALTER TABLE documents ADD COLUMN chunk_strategy TEXT NOT NULL DEFAULT 'paragraph'");
            if (!columns.contains("chunk_size")) update(db, "ALTER TABLE documents ADD COLUMN chunk_size INTEGER NOT NULL DEFAULT " + settings.chunkSize());
            if (!columns.contains("chunk_overlap")) update(db, "ALTER TABLE documents ADD COLUMN chunk_overlap INTEGER NOT NULL DEFAULT " + settings.overlap());
            return null;
        });
    }
    private Connection connect() throws SQLException {
        Connection db = DriverManager.getConnection(url);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=30000");
        }
        return db;
    }
    <T> T read(Work<T> work) {
        try (Connection db = connect()) { return work.run(db); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("数据库操作失败", e); }
    }
    <T> T transaction(Work<T> work) {
        return read(db -> {
            try (Statement s = db.createStatement()) {
                s.execute("BEGIN IMMEDIATE");
                try {
                    T result = work.run(db);
                    s.execute("COMMIT");
                    return result;
                } catch (Exception e) {
                    s.execute("ROLLBACK");
                    throw e;
                }
            }
        });
    }
    static int update(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s.executeUpdate();
        }
    }
    static List<Map<String, Object>> query(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet rows = s.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                        row.put(rows.getMetaData().getColumnLabel(i), rows.getObject(i));
                    result.add(row);
                }
                return result;
            }
        }
    }
    static Map<String, Object> one(Connection db, String sql, Object... args) throws SQLException {
        var rows = query(db, sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Map<String, Object> document(String id) {
        return read(db -> one(db, "SELECT * FROM documents WHERE id=?", id));
    }
    public List<Map<String, Object>> documents(String collection) {
        return read(db -> query(db, "SELECT id,title,source,version,updated_at,chunk_strategy,chunk_size,chunk_overlap FROM documents WHERE collection=? ORDER BY title", collection));
    }
    List<Map<String, Object>> chunks(String collection) {
        return read(db -> query(db, "SELECT c.*,d.title,d.source,d.version FROM chunks c JOIN documents d ON c.document_id=d.id WHERE d.collection=? ORDER BY c.rowid", collection));
    }
    public boolean delete(String id) {
        return transaction(db -> update(db, "DELETE FROM documents WHERE id=?", id) > 0);
    }
    void saveTrace(String id, Map<String, Object> trace) {
        read(db -> update(db, "INSERT INTO traces (id,data) VALUES (?,?)", id, Json.write(trace)));
    }
    public Map<String, Object> trace(String id) {
        return read(db -> {
            var row = one(db, "SELECT * FROM traces WHERE id=?", id);
            if (row == null) return null;
            var result = Json.map("id", id, "created_at", row.get("created_at"));
            result.putAll(Json.object((String) row.get("data")));
            return result;
        });
    }
}
