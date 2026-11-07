package com.partner.contracts;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

final class Storage {
    private final String url;
    Storage(Path path) throws Exception {
        url = "jdbc:sqlite:" + path;
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            try (var stream = getClass().getResourceAsStream("/schema.sql")) {
                for (String sql : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split(";"))
                    if (!sql.isBlank()) s.execute(sql);
            }
        }
        CredentialStore.permissions(path, false);
    }
    Tx begin(boolean write) {
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout=20000"); s.execute("PRAGMA foreign_keys=ON");
                s.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
            } catch (Exception e) { c.close(); throw e; }
            return new Tx(c);
        } catch (SQLException e) { throw new IllegalStateException("Storage unavailable", e); }
    }
    boolean pqcKeysInitialized() {
        try (var db = begin(false)) {
            return db.one("SELECT 1 FROM metadata WHERE key='pqc_keys_initialized'") != null;
        }
    }
    boolean hasPqcArtifacts() {
        try (var db = begin(false)) {
            return db.one("SELECT 1 FROM versions WHERE envelope LIKE '%contract-envelope-v2%' LIMIT 1") != null
                || db.one("SELECT 1 FROM comments WHERE content LIKE '%contract-envelope-v2%' LIMIT 1") != null
                || db.one("SELECT 1 FROM approvals WHERE scheme='ML-DSA-65' LIMIT 1") != null;
        }
    }
    void markPqcKeysInitialized() {
        try (var db = begin(true)) {
            db.run("INSERT OR IGNORE INTO metadata(key, value) VALUES ('pqc_keys_initialized', '1')");
            db.commit();
        }
    }
    static final class Tx implements AutoCloseable {
        private final Connection connection;
        private boolean committed;
        Tx(Connection connection) { this.connection = connection; }
        List<Map<String, Object>> rows(String sql, Object... args) {
            try (PreparedStatement s = prepare(sql, args); ResultSet r = s.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
                    result.add(row);
                }
                return result;
            } catch (SQLException e) { throw new IllegalStateException("Storage query failed", e); }
        }
        Map<String, Object> one(String sql, Object... args) {
            var rows = rows(sql, args); return rows.isEmpty() ? null : rows.getFirst();
        }
        void run(String sql, Object... args) {
            try (PreparedStatement s = prepare(sql, args)) { s.executeUpdate(); }
            catch (SQLException e) { throw new IllegalStateException("Storage update failed", e); }
        }
        private PreparedStatement prepare(String sql, Object... args) throws SQLException {
            PreparedStatement s = connection.prepareStatement(sql);
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s;
        }
        void commit() {
            try (Statement s = connection.createStatement()) { s.execute("COMMIT"); committed = true; }
            catch (SQLException e) { throw new IllegalStateException("Storage commit failed", e); }
        }
        public void close() {
            try {
                if (!committed) try (Statement s = connection.createStatement()) { s.execute("ROLLBACK"); }
            } catch (SQLException e) { throw new IllegalStateException("Storage rollback failed", e); }
            finally { try { connection.close(); } catch (SQLException e) { throw new IllegalStateException(e); } }
        }
    }
}
